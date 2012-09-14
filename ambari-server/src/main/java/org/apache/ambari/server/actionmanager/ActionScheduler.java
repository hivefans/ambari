/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.actionmanager;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.ambari.server.Role;
import org.apache.ambari.server.agent.ActionQueue;
import org.apache.ambari.server.agent.AgentCommand;
import org.apache.ambari.server.agent.ExecutionCommand;

//This class encapsulates the action scheduler thread. 
//Action schedule frequently looks at action database and determines if
//there is an action that can be scheduled.
class ActionScheduler implements Runnable {
  
  private final long actionTimeout;
  private final long sleepTime;
  private volatile boolean shouldRun = true;
  private Thread schedulerThread = null;
  private final ActionDBAccessor db;
  private final short maxAttempts = 2;
  private final ActionQueue actionQueue;
  
  public ActionScheduler(long sleepTimeMilliSec, long actionTimeoutMilliSec,
      ActionDBAccessor db, ActionQueue actionQueue) {
    this.sleepTime = sleepTimeMilliSec;
    this.actionTimeout = actionTimeoutMilliSec;
    this.db = db;
    this.actionQueue = actionQueue;
  }
  
  public void start() {
    schedulerThread = new Thread(this);
    schedulerThread.start();
  }
  
  public void stop() {
    shouldRun = false;
    schedulerThread.interrupt();
  }

  @Override
  public void run() {
    while (shouldRun) {
      try {
        doWork();
        Thread.sleep(sleepTime);
      } catch (InterruptedException ex) {
        shouldRun = false;
      } catch (Exception ex) {
        //ignore
        //Log the exception
      }
    }
  }
  
  private void doWork() {
    List<Stage> stages = db.getQueuedStages();
    if (stages == null || stages.isEmpty()) {
      //Nothing to do
      return;
    }
    
    //First discover completions and timeouts.
    boolean operationFailure = false;
    for (Stage s : stages) {
      Map<Role, Map<String, HostRoleCommand>> roleToHrcMap = getInvertedRoleMap(s);
      
      //Iterate for completion
      boolean moveToNextStage = true;
      for (Role r: roleToHrcMap.keySet()) {
        processPendingsAndReschedule(s, roleToHrcMap.get(r));
        RoleStatus roleStatus = getRoleStatus(roleToHrcMap.get(r), s.getSuccessFactor(r));
        if (!roleStatus.isRoleSuccessful()) {
          if (!roleStatus.isRoleInProgress()) {
            //The role has completely failed
            //Mark the entire operation as failed
            operationFailure = true;
            break;
          }
          moveToNextStage = false;
        }
      }
      if (operationFailure) {
        db.abortOperation(s.getRequestId());
      }
      if (operationFailure || !moveToNextStage) {
        break;
      }
    }
  }

  private void processPendingsAndReschedule(Stage stage,
      Map<String, HostRoleCommand> hrcMap) {
    for (String host : hrcMap.keySet()) {
      HostRoleCommand hrc = hrcMap.get(host);
      long now = System.currentTimeMillis();
      if (now > hrc.getExpiryTime()) {
        // expired
        if (now > hrc.getStartTime() + actionTimeout * maxAttempts) {
          // final expired
          db.timeoutHostRole(stage.getRequestId(), stage.getStageId(), hrc.getRole());
        } else {
          rescheduleHostRole(stage, hrc);
        }
      }
    }
  }

  private void rescheduleHostRole(Stage s,
      HostRoleCommand hrc) {
    long now = System.currentTimeMillis();
    hrc.setExpiryTime(now);
    ExecutionCommand cmd = new ExecutionCommand();
    cmd.setCommandId(s.getActionId());
    cmd.setManifest(s.getManifest(hrc.getHostName()));
    actionQueue.enqueue(hrc.getHostName(), cmd);
  }

  private RoleStatus getRoleStatus(
      Map<String, HostRoleCommand> hostRoleCmdForRole, float successFactor) {
    RoleStatus rs = new RoleStatus(hostRoleCmdForRole.size(), successFactor);
    for (String h : hostRoleCmdForRole.keySet()) {
      HostRoleCommand hrc = hostRoleCmdForRole.get(h);
      switch (hrc.getStatus()) {
      case COMPLETED:
        rs.numSucceeded++;
        break;
      case FAILED:
        rs.numFailed++;
        break;
      case QUEUED:
        rs.numQueued++;
        break;
      case PENDING:
        rs.numPending++;
        break;
      case TIMEDOUT:
        rs.numTimedOut++;
        break;
      case ABORTED:
        rs.numAborted++;
      }
    }
    return rs;
  }

  private Map<Role, Map<String, HostRoleCommand>> getInvertedRoleMap(Stage s) {
    // Temporary to store role to host
    Map<Role, Map<String, HostRoleCommand>> roleToHrcMap = new TreeMap<Role, Map<String, HostRoleCommand>>();
    Map<String, HostAction> hostActions = s.getHostActions();
    for (String h : hostActions.keySet()) {
      HostAction ha = hostActions.get(h);
      List<HostRoleCommand> roleCommands = ha.getRoleCommands();
      for (HostRoleCommand hrc : roleCommands) {
        Map<String, HostRoleCommand> hrcMap = roleToHrcMap.get(hrc.getRole());
        if (hrcMap == null) {
          hrcMap = new TreeMap<String, HostRoleCommand>();
          roleToHrcMap.put(hrc.getRole(), hrcMap);
        }
        hrcMap.put(h, hrc);
      }
    }
    return roleToHrcMap;
  }
  
  static class RoleStatus {
    int numQueued = 0;
    int numSucceeded = 0;
    int numFailed = 0;
    int numTimedOut = 0;
    int numPending = 0;
    int numAborted = 0;
    final int totalHosts;
    final float successFactor;
    
    RoleStatus(int total, float successFactor) {
      this.totalHosts = total;
      this.successFactor = successFactor;
    }
    
    boolean isRoleSuccessful() {
      if (successFactor <= (1.0*numSucceeded)/totalHosts) {
        return true;
      } else {
        return false;
      }
    }
    
    boolean isRoleInProgress() {
      return (numPending+numQueued > 0);
    }
    
    boolean isRoleFailed() {
      if ((!isRoleInProgress()) && (!isRoleSuccessful())) {
        return false;
      } else {
        return true;
      }
    }
  }
}
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

package org.apache.ambari.server.state.svccomphost;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.ambari.server.controller.ServiceComponentHostResponse;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceComponentHostEvent;
import org.apache.ambari.server.state.ServiceComponentHostEventType;
import org.apache.ambari.server.state.StackVersion;
import org.apache.ambari.server.state.fsm.InvalidStateTransitonException;
import org.apache.ambari.server.state.fsm.SingleArcTransition;
import org.apache.ambari.server.state.fsm.StateMachine;
import org.apache.ambari.server.state.fsm.StateMachineFactory;
import org.apache.ambari.server.state.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceComponentHostImpl implements ServiceComponentHost {

  private static final Logger LOG =
      LoggerFactory.getLogger(ServiceComponentHostImpl.class);

  private final Lock readLock;
  private final Lock writeLock;

  private final ServiceComponent serviceComponent;
  private final String hostName;

  private Map<String, Config> configs;
  private Map<String, Config> desiredConfigs;
  private StackVersion stackVersion;
  private StackVersion desiredStackVersion;

  private State desiredState;

  private long lastOpStartTime;
  private long lastOpEndTime;
  private long lastOpLastUpdateTime;

  private static final StateMachineFactory
  <ServiceComponentHostImpl, State,
  ServiceComponentHostEventType, ServiceComponentHostEvent>
    daemonStateMachineFactory
      = new StateMachineFactory<ServiceComponentHostImpl,
          State, ServiceComponentHostEventType,
          ServiceComponentHostEvent>
          (State.INIT)

  // define the state machine of a HostServiceComponent for runnable
  // components

     .addTransition(State.INIT,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLING,
         State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLED,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_START,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.STARTING,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.STARTING,
         State.STARTED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.STARTING,
         State.START_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.START_FAILED,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.STARTED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOP,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.STOPPING,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.STOPPING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.STOPPING,
         State.STOP_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STOP_FAILED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.UNINSTALL_FAILED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.WIPING_OUT,
         State.INIT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPEOUT_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPEOUT_FAILED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .installTopology();

  private static final StateMachineFactory
  <ServiceComponentHostImpl, State,
  ServiceComponentHostEventType, ServiceComponentHostEvent>
    clientStateMachineFactory
      = new StateMachineFactory<ServiceComponentHostImpl,
          State, ServiceComponentHostEventType,
          ServiceComponentHostEvent>
          (State.INIT)

  // define the state machine of a HostServiceComponent for client only
  // components

     .addTransition(State.INIT,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLING,
         State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.UNINSTALL_FAILED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.WIPING_OUT,
         State.INIT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPEOUT_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPEOUT_FAILED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .installTopology();


  private final StateMachine<State,
      ServiceComponentHostEventType, ServiceComponentHostEvent> stateMachine;

  static class ServiceComponentHostOpCompletedTransition
     implements SingleArcTransition<ServiceComponentHostImpl,
         ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
    }

  }

  static class ServiceComponentHostOpStartedTransition
    implements SingleArcTransition<ServiceComponentHostImpl,
        ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
    }
  }

  static class ServiceComponentHostOpInProgressTransition
    implements SingleArcTransition<ServiceComponentHostImpl,
        ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
    }
  }


  private void resetLastOpInfo() {
    try {
      writeLock.lock();
      setLastOpStartTime(-1);
      setLastOpLastUpdateTime(-1);
      setLastOpEndTime(-1);
    }
    finally {
      writeLock.unlock();
    }
  }

  private void updateLastOpInfo(ServiceComponentHostEventType eventType,
      long time) {
    try {
      writeLock.lock();
      switch (eventType) {
        case HOST_SVCCOMP_INSTALL:
        case HOST_SVCCOMP_START:
        case HOST_SVCCOMP_STOP:
        case HOST_SVCCOMP_UNINSTALL:
        case HOST_SVCCOMP_WIPEOUT:
        case HOST_SVCCOMP_OP_RESTART:
          resetLastOpInfo();
          setLastOpStartTime(time);
          break;
        case HOST_SVCCOMP_OP_FAILED:
        case HOST_SVCCOMP_OP_SUCCEEDED:
          setLastOpLastUpdateTime(time);
          setLastOpEndTime(time);
          break;
        case HOST_SVCCOMP_OP_IN_PROGRESS:
          setLastOpLastUpdateTime(time);
          break;
      }
    }
    finally {
      writeLock.unlock();
    }
  }

  public ServiceComponentHostImpl(ServiceComponent serviceComponent,
      String hostName, boolean isClient) {
    super();
    if (isClient) {
      this.stateMachine = clientStateMachineFactory.make(this);
    } else {
      this.stateMachine = daemonStateMachineFactory.make(this);
    }
    ReadWriteLock rwLock = new ReentrantReadWriteLock();
    this.readLock = rwLock.readLock();
    this.writeLock = rwLock.writeLock();
    this.serviceComponent = serviceComponent;
    this.hostName = hostName;
    this.resetLastOpInfo();
    this.desiredConfigs = new HashMap<String, Config>();
    this.desiredStackVersion = new StackVersion("");
    this.desiredState = State.INIT;
    this.configs = new HashMap<String, Config>();
    this.stackVersion = new StackVersion("");
  }

  @Override
  public State getState() {
    try {
      readLock.lock();
      return stateMachine.getCurrentState();
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public void setState(State state) {
    try {
      writeLock.lock();
      stateMachine.setCurrentState(state);
    }
    finally {
      writeLock.unlock();
    }
  }

  @Override
  public void handleEvent(ServiceComponentHostEvent event)
      throws InvalidStateTransitonException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Handling ServiceComponentHostEvent event,"
          + " eventType=" + event.getType().name()
          + ", event=" + event.toString());
    }
    State oldState = getState();
    try {
      writeLock.lock();
      try {
        stateMachine.doTransition(event.getType(), event);
        // TODO Audit logs
      } catch (InvalidStateTransitonException e) {
        LOG.error("Can't handle ServiceComponentHostEvent event at"
            + " current state"
            + ", serviceComponentName=" + this.getServiceComponentName()
            + ", hostName=" + this.getHostName()
            + ", currentState=" + oldState
            + ", eventType=" + event.getType()
            + ", event=" + event);
        throw e;
      }
    }
    finally {
      writeLock.unlock();
    }
    if (!oldState.equals(getState())) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("ServiceComponentHost transitioned to a new state"
            + ", serviceComponentName=" + this.getServiceComponentName()
            + ", hostName=" + this.getHostName()
            + ", oldState=" + oldState
            + ", currentState=" + getState()
            + ", eventType=" + event.getType().name()
            + ", event=" + event);
      }
    }
  }

  @Override
  public List<Job> getJobs() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public String getServiceComponentName() {
    return serviceComponent.getName();
  }

  @Override
  public String getHostName() {
    return hostName;
  }

  /**
   * @return the lastOpStartTime
   */
  public long getLastOpStartTime() {
    try {
      readLock.lock();
      return lastOpStartTime;
    }
    finally {
      readLock.unlock();
    }
  }

  /**
   * @param lastOpStartTime the lastOpStartTime to set
   */
  public void setLastOpStartTime(long lastOpStartTime) {
    try {
      writeLock.lock();
      this.lastOpStartTime = lastOpStartTime;
    }
    finally {
      writeLock.unlock();
    }
  }

  /**
   * @return the lastOpEndTime
   */
  public long getLastOpEndTime() {
    try {
      readLock.lock();
      return lastOpEndTime;
    }
    finally {
      readLock.unlock();
    }
  }

  /**
   * @param lastOpEndTime the lastOpEndTime to set
   */
  public void setLastOpEndTime(long lastOpEndTime) {
    try {
      writeLock.lock();
      this.lastOpEndTime = lastOpEndTime;
    }
    finally {
      writeLock.unlock();
    }
  }

  /**
   * @return the lastOpLastUpdateTime
   */
  public long getLastOpLastUpdateTime() {
    try {
      readLock.lock();
      return lastOpLastUpdateTime;
    }
    finally {
      readLock.unlock();
    }
  }

  /**
   * @param lastOpLastUpdateTime the lastOpLastUpdateTime to set
   */
  public void setLastOpLastUpdateTime(long lastOpLastUpdateTime) {
    try {
      writeLock.lock();
      this.lastOpLastUpdateTime = lastOpLastUpdateTime;
    }
    finally {
      writeLock.unlock();
    }
  }

  @Override
  public long getClusterId() {
    return serviceComponent.getClusterId();
  }

  @Override
  public String getServiceName() {
    return serviceComponent.getServiceName();
  }

  @Override
  public Map<String, Config> getConfigs() {
    try {
      readLock.lock();
      return Collections.unmodifiableMap(configs);
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public void updateConfigs(Map<String, Config> configs) {
    try {
      writeLock.lock();
      this.configs.putAll(configs);
    }
    finally {
      writeLock.unlock();
    }
  }

  @Override
  public StackVersion getStackVersion() {
    try {
      readLock.lock();
      return stackVersion;
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public void setStackVersion(StackVersion stackVersion) {
    try {
      writeLock.lock();
      this.stackVersion = stackVersion;
    }
    finally {
      writeLock.unlock();
    }
  }


  @Override
  public State getDesiredState() {
    try {
      readLock.lock();
      return desiredState;
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public void setDesiredState(State state) {
    try {
      writeLock.lock();
      this.desiredState = state;
    }
    finally {
      writeLock.unlock();
    }
  }

  @Override
  public Map<String, Config> getDesiredConfigs() {
    try {
      readLock.lock();
      return Collections.unmodifiableMap(desiredConfigs);
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public void updateDesiredConfigs(Map<String, Config> configs) {
    try {
      writeLock.lock();
      this.desiredConfigs.putAll(configs);
    }
    finally {
      writeLock.unlock();
    }
  }

  @Override
  public StackVersion getDesiredStackVersion() {
    try {
      readLock.lock();
      return desiredStackVersion;
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public void setDesiredStackVersion(StackVersion stackVersion) {
    try {
      writeLock.lock();
      this.desiredStackVersion = stackVersion;
    }
    finally {
      writeLock.unlock();
    }
  }

  private Map<String, String> getConfigVersions() {
    try {
      readLock.lock();
      Map<String, String> configVersions = new HashMap<String, String>();
      for (Config c : configs.values()) {
        configVersions.put(c.getType(), c.getVersionTag());
      }
      return configVersions;
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public ServiceComponentHostResponse convertToResponse() {
    try {
      readLock.lock();
      ServiceComponentHostResponse r = new ServiceComponentHostResponse(
          serviceComponent.getClusterName(),
          serviceComponent.getServiceName(),
          serviceComponent.getName(),
          hostName,
          getConfigVersions(),
          getState().toString(),
          getStackVersion().getStackVersion(),
          getDesiredState().toString());
      return r;
    }
    finally {
      readLock.unlock();
    }
  }

  @Override
  public String getClusterName() {
    return serviceComponent.getClusterName();
  }

}
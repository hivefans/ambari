<?php
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/


include_once "../util/Logger.php";

class DBReader {

  private $logger;
  private $dbPath;

  function __construct($db) {
    $this->dbPath = $db;
    $this->logger = new HMCLogger("DBReader");
  }

  public function getHadoopStackVersion($clusterName, $dbAccessor = NULL) {
    if ($dbAccessor == NULL) {
      $dbAccessor = new HMCDBAccessor($this->dbPath);
    }
    $ret = $dbAccessor->getHadoopStackVersion($clusterName);
    return $ret["version"];
  }

  public function getAllConfigs($clusterId) {
    $dbAccessor = new HMCDBAccessor($this->dbPath);
    $allConfigs = $dbAccessor->getServiceConfig($clusterId);
    $puppetConfigs = array();
    foreach ($allConfigs["properties"] as $key => $value) {
      $puppetValue = $value;
      if (isset($this->configValuePrefix[$key])) {
        $puppetValue = $this->configValuePrefix[$key] . $puppetValue;
      }
      if (isset($this->configValueSuffix[$key])) {
        $puppetValue = $puppetValue . $this->configValueSuffix[$key] ;
      }
      $puppetConfigs[$key] = $puppetValue;
    }
    return $puppetConfigs;
  }

  public function getAllHostAttributes($clusterName) {
    $dbAccessor = new HMCDBAccessor($this->dbPath);
    $allHostAttributes = $dbAccessor->getAllHostsInfo($clusterName,
        array("=" => array ( "discoveryStatus" => "SUCCESS")), array());
    return $allHostAttributes;
  }

  public function getHostNames($clusterId) {
    $dbAccessor = new HMCDBAccessor($this->dbPath);
    $allHostComponents = $dbAccessor->getAllHostsByComponent($clusterId);
    $hadoopVersion = $this->getHadoopStackVersion($clusterId, $dbAccessor);
    $hostKeyMap = ($hadoopVersion == AMBARI_HADOOP_2) ? $this->hadoop2_hostKeyMap : $this->hadoop1_hostKeyMap;
    $hostNames = array();
    foreach ($allHostComponents["components"] as $componentName => $componentInfo) {
      if (!isset($componentInfo["hosts"])) {
        continue;
      }
      foreach($componentInfo["hosts"] as $hostname => $hostInfo) {
        if (!isset($hostKeyMap[$componentName])) {
          $this->logger->log_trace("No puppet handling needed for  component "
              . $componentName);
          continue;
        }
        $puppetHostKey = $hostKeyMap[$componentName];
        if (!isset($hostNames[$puppetHostKey])) {
          $hostNames[$puppetHostKey] = array();
        }
        if (!in_array($hostname, $hostNames[$puppetHostKey])) {
          $hostNames[$puppetHostKey][] = $hostname;
        }
      }
    }
    return $hostNames;
  }

  function getHostRolesStates($clusterId, $nodes, $components) {
    //get roles for each host from db
    $dbAccessor = new HMCDBAccessor($this->dbPath);
    $allHostComponents = $dbAccessor->getAllHostsByComponent($clusterId);
    $allHostRoleConfigs = $dbAccessor->getHostRoleConfigs($clusterId);
    $hadoopVersion = $this->getHadoopStackVersion($clusterId, $dbAccessor);
    $componentToPuppetClassMap = ($hadoopVersion == AMBARI_HADOOP_2) ? $this->hadoop2_componentToPuppetClassMap : $this->hadoop1_componentToPuppetClassMap;
    $hostRoles = array();
    foreach ($allHostComponents["components"] as $componentName => $componentInfo) {
      if (!isset($componentInfo["hosts"])) {
        continue;
      }
      foreach($componentInfo["hosts"] as $hostname => $hostInfo) {
        if (!in_array($hostname, $nodes)) {
          continue;
        }
        if (!isset($hostRoles[$hostname])) {
          $hostRoles[$hostname] = array();
        }
        if (!isset($componentToPuppetClassMap[$componentName])) {
          $this->logger->log_trace("No puppet handling needed for component "
              . $componentName);
          continue;
        }
        $puppetClass = $componentToPuppetClassMap[$componentName];
        $hostRoles[$hostname][$puppetClass] = array();
        if (in_array($componentName, $components)) {
            $hostRoles[$hostname][$puppetClass]["service_state"] =
                $hostInfo["desiredState"];
        } else {
            $hostRoles[$hostname][$puppetClass]["service_state"] = "no_op";
        }

        if (isset($allHostRoleConfigs["properties"])
            && isset($allHostRoleConfigs["properties"][$componentName])
            && isset($allHostRoleConfigs["properties"][$componentName][$hostname])) {
          foreach ($allHostRoleConfigs["properties"][$componentName][$hostname] as $key => $val) {
            $hostRoles[$hostname][$puppetClass][$key] = $val;
          }
        }
      }
    }
    return $hostRoles;
  }

  private $hadoop1_componentToPuppetClassMap = array (
      "NAMENODE" => "hdp-hadoop::namenode",
      "DATANODE"=> "hdp-hadoop::datanode",
      "SNAMENODE" => "hdp-hadoop::snamenode",
      "JOBTRACKER" => "hdp-hadoop::jobtracker",
      "TASKTRACKER" => "hdp-hadoop::tasktracker",
      "HDFS_CLIENT" => "hdp-hadoop::client",
      "MAPREDUCE_CLIENT" => "hdp-hadoop::client",
  		"ZOOKEEPER_SERVER" => "hdp-zookeeper",
      "ZOOKEEPER_CLIENT" => "hdp-zookeeper::client",
      "HBASE_MASTER" => "hdp-hbase::master",
      "HBASE_REGIONSERVER" => "hdp-hbase::regionserver",
      "HBASE_CLIENT" => "hdp-hbase::client",
      "PIG_CLIENT" => "hdp-pig",
      "SQOOP_CLIENT" => "hdp-sqoop",
      "OOZIE_SERVER" => "hdp-oozie::server",
      "OOZIE_CLIENT" => "hdp-oozie::client",
      "HIVE_CLIENT" => "hdp-hive::client",
      "HCATALOG_CLIENT" => "hdp-hcat",
      "HCATALOG_SERVER" => "hdp-hcat::server",
      "HIVE_SERVER" => "hdp-hive::server",
      "HIVE_MYSQL" => "hdp-mysql::server",
      "TEMPLETON_SERVER" => "hdp-templeton::server",
      "TEMPLETON_CLIENT" => "hdp-templeton::client",
      "DASHBOARD" => "hdp-dashboard",
      "NAGIOS_SERVER" => "hdp-nagios::server",
      "GANGLIA_MONITOR_SERVER" => "hdp-ganglia::server",
      "GANGLIA_MONITOR" => "hdp-ganglia::monitor",
      "HTTPD" => "hdp-monitor-webserver"
    );

  private $hadoop2_componentToPuppetClassMap = array (
      "NAMENODE2" => "hdp2-hadoop::namenode",
      "DATANODE2"=> "hdp2-hadoop::datanode",
      "SNAMENODE2" => "hdp2-hadoop::snamenode",
      "RESOURCEMANAGER" => "hdp2-hadoop::resourcemanager",
      "NODEMANAGER" => "hdp2-hadoop::nodemanager",
      "HDFS2_CLIENT" => "hdp2-hadoop::client",
      "YARN_CLIENT" => "hdp2-hadoop::client",
  		"MAPREDUCE2_CLIENT" => "hdp2-hadoop::client",
  		"ZOOKEEPER2_SERVER" => "hdp2-zookeeper",
      "ZOOKEEPER2_CLIENT" => "hdp2-zookeeper::client",
      "HBASE2_MASTER" => "hdp2-hbase::master",
      "HBASE2_REGIONSERVER" => "hdp2-hbase::regionserver",
      "HBASE2_CLIENT" => "hdp2-hbase::client",
      "PIG2_CLIENT" => "hdp2-pig",
      "SQOOP_CLIENT" => "hdp-sqoop",
      "OOZIE2_SERVER" => "hdp2-oozie::server",
      "OOZIE2_CLIENT" => "hdp2-oozie::client",
      "HIVE2_CLIENT" => "hdp2-hive::client",
      "HCATALOG2_CLIENT" => "hdp2-hcat",
      "HCATALOG2_SERVER" => "hdp2-hcat::server",
      "HIVE2_SERVER" => "hdp2-hive::server",
      "HIVE2_MYSQL" => "hdp2-mysql::server",
      "TEMPLETON_SERVER" => "hdp-templeton::server",
      "TEMPLETON_CLIENT" => "hdp-templeton::client",
      "DASHBOARD" => "hdp-dashboard",
      "NAGIOS_SERVER2" => "hdp2-nagios::server",
      "GANGLIA2_MONITOR_SERVER" => "hdp2-ganglia::server",
      "GANGLIA2_MONITOR" => "hdp2-ganglia::monitor",
      "HTTPD" => "hdp-monitor-webserver"
    );

  //Store the database key for each role
  private $hadoop1_hostKeyMap =
    array(
        "NAMENODE" => "namenode_host",
        "JOBTRACKER" => "jtnode_host",
        "SNAMENODE" => "snamenode_host",
    		"ZOOKEEPER_SERVER" => "zookeeper_hosts",
        "HBASE_MASTER" => "hbase_master_host",
        "HCATALOG_SERVER" => "hcat_server_host",
        "HIVE_SERVER" => "hive_server_host",
        "OOZIE_SERVER" => "oozie_server",
        "TEMPLETON_SERVER" => "templeton_server_host",
        "DASHBOARD" => "dashboard_host",
        "NAGIOS_SERVER" => "nagios_server_host",
        "GANGLIA_MONITOR_SERVER" => "ganglia_server_host",
        "DATANODE" => "slave_hosts",
        "TASKTRACKER" => "slave_hosts",
        "HBASE_REGIONSERVER" => "hbase_rs_hosts"
        );

  private $hadoop2_hostKeyMap =
    array(
        "NAMENODE2" => "namenode_host",
        "RESOURCEMANAGER" => "yarn_rm_host",
        "SNAMENODE2" => "snamenode_host",
    		"ZOOKEEPER2_SERVER" => "zookeeper_hosts",
        "HBASE2_MASTER" => "hbase_master_host",
        "HCATALOG2_SERVER" => "hcat_server_host",
        "HIVE2_SERVER" => "hive_server_host",
        "OOZIE2_SERVER" => "oozie_server",
        "TEMPLETON_SERVER" => "templeton_server_host",
        "DASHBOARD" => "dashboard_host",
        "NAGIOS_SERVER2" => "nagios_server_host",
        "GANGLIA2_MONITOR_SERVER" => "ganglia_server_host",
        "DATANODE2" => "slave_hosts",
        "NODEMANAGER" => "slave_hosts",
        "HBASE2_REGIONSERVER" => "hbase_rs_hosts"
        );

  /****** Not used but may be used later *****
  //Store the database key for each configuration
  private $configKeyMap =
    array(
        "mapred_user" => "mapred_user",
        "hdfs_user" => "hdfs_user",
        "dataNodeDir" => "dfs_data_dir",
        "nameNodeDir" => "dfs_name_dir",
        "dfs_replication" => "dfs_replication",
        "mapred_local_dir" => "mapred_local_dir",
        "hadoop_logdirprefix" => "hadoop_logdirprefix",
        "hadoop_piddirprefix" => "hadoop_piddirprefix",
        "zk_user" => "zk_user",
        "zk_log_dir" => "zk_log_dir",
        "zk_data_dir" => "zk_data_dir",
        "zk_pid_dir" => "zk_pid_dir",
        "hbase_user" => "hbase_user",
        "hbase_log_dir" => "hbase_log_dir",
        "hbase_pid_dir" => "hbase_pid_dir",
        "hcat_user" => "hcat_user",
        "hcat_database_name" => "hcat_database_name",
        "hcat_metastore_user_name" => "hcat_metastore_user_name",
        "hcat_metastore_user_passwd" => "hcat_metastore_user_passwd",
        "hcat_logdirprefix" => "hcat_logdirprefix",
        "hcat_piddirprefix" => "hcat_piddirprefix",
        "oozie_user" => "oozie_user",
        "oozie_log_dir" => "oozie_log_dir",
        "oozie_pid_dir" => "oozie_pid_dir",
        "oozie_data_dir" => "oozie_data_dir",
        "templeton_user" => "templeton_user",
        "templeton_log_dir" => "templeton_log_dir",
        "templeton_pid_dir" => "templeton_pid_dir"
          );

  /***  Not used but may be used later *****
  private $serviceStateMap = array(
       "running" => "running",
       "installed_and_configured" => "installed_and_configured",
       "no_op" => "no_op",
       "stopped" => "stopped"
    );
  ******/

  private $configValueSuffix = array (
     "hadoop_heapsize" => "m",
     "namenode_heapsize" => "m",
     "namenode_opt_newsize" => "m",
     "dtnode_heapsize" => "m",
     "jtnode_opt_newsize" => "m",
     "jtnode_opt_maxnewsize" => "m",
     "jtnode_heapsize" => "m",
     "mapred_child_java_opts_sz" => "m",
     "hbase_master_heapsize" => "m",
     "hbase_regionserver_heapsize" => "m"
  );

  private $configValuePrefix = array (
     "mapred_child_java_opts_sz" => "-Xmx"
  );
}

?>

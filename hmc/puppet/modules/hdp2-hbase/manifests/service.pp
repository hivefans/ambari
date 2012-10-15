#
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
#
define hdp2-hbase::service(
  $ensure = 'running',
  $create_pid_dir = true,
  $create_log_dir = true,
  $initial_wait = undef)
{
  include hdp2-hbase::params

  $role = $name
  $user = $hdp2-hbase::params::hbase_user

  $conf_dir = $hdp2::params::hbase_conf_dir
  $hbase_daemon = $hdp2::params::hbase_daemon_script
  $cmd = "$hbase_daemon --config ${conf_dir}"
  $pid_dir = $hdp2-hbase::params::hbase_pid_dir
  $pid_file = "${pid_dir}/hbase-hbase-${role}.pid"

  if ($ensure == 'running') {
    $daemon_cmd = "su - ${user} -c  '${cmd} start ${role}'"
    $no_op_test = "ls ${pid_file} >/dev/null 2>&1 && ps `cat ${pid_file}` >/dev/null 2>&1"
  } elsif ($ensure == 'stopped') {
    $daemon_cmd = "su - ${user} -c  '${cmd} stop ${role}'"
    $no_op_test = undef
  } else {
    $daemon_cmd = undef
  }

  $tag = "hbase_service-${name}"
  
  if ($create_pid_dir == true) {
    hdp2::directory_recursive_create { $pid_dir: 
      owner => $user,
      tag   => $tag,
      service_state => $ensure,
      force => true
    }
  }
  if ($create_log_dir == true) {
    hdp2::directory_recursive_create { $hdp2-hbase::params::hbase_log_dir: 
      owner => $user,
      tag   => $tag,
      service_state => $ensure,
      force => true
    }
  }

  anchor{"hdp2-hbase::service::${name}::begin":} -> Hdp2::Directory_recursive_create<|tag == $tag|> -> anchor{"hdp2-hbase::service::${name}::end":}
  if ($daemon_cmd != undef) { 
    hdp2::exec { $daemon_cmd:
      command      => $daemon_cmd,
      unless       => $no_op_test,
      initial_wait => $initial_wait
    }
    Hdp2::Directory_recursive_create<|context_tag == 'hbase_service'|> -> Hdp2::Exec[$daemon_cmd] -> Anchor["hdp2-hbase::service::${name}::end"]
  }
}

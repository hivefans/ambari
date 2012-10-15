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
class hdp2-hbase::client(
  $service_state = $hdp2::params::cluster_client_state,
  $opts = {}
)
{
  #assumption is there are no other hbase components on node
  if ($service_state == 'no_op') {
  } elsif ($service_state in ['installed_and_configured','uninstalled']) {
    if (($hdp2::params::service_exists['hdp2-hbase::master'] != true) and ($hdp2::params::service_exists['hdp2-hbase::regionserver'] != true)) {
      #adds package, users, directories, and common configs
      class { 'hdp2-hbase': 
        type          => 'client',
        service_state => $service_state
      }
    }
  } else {
    hdp_fail("TODO not implemented yet: service_state = ${service_state}")
  }
}

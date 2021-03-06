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

package org.apache.ambari.server.controller.jmx;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.*;
import org.apache.ambari.server.controller.internal.AbstractProviderModule;
import org.apache.ambari.server.controller.internal.DefaultProviderModule;
import org.apache.ambari.server.controller.internal.ResourceImpl;
import org.apache.ambari.server.controller.spi.*;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.state.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import java.util.*;


/**
 * JMX property provider tests.
 */
public class JMXPropertyProviderTest {
  protected static final String HOST_COMPONENT_HOST_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("HostRoles", "host_name");
  protected static final String HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("HostRoles", "component_name");

  @Test
  public void testGetResources() throws Exception {
    TestStreamProvider  streamProvider = new TestStreamProvider();
    TestJMXHostProvider hostProvider = new TestJMXHostProvider();

    JMXPropertyProvider propertyProvider = new JMXPropertyProvider(
        PropertyHelper.getJMXPropertyIds(Resource.Type.HostComponent),
        streamProvider,
        hostProvider, PropertyHelper.getPropertyId("HostRoles", "cluster_name"), PropertyHelper.getPropertyId("HostRoles", "host_name"), PropertyHelper.getPropertyId("HostRoles", "component_name"));

    // namenode
    Resource resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domu-12-31-39-0e-34-e1.compute-1.internal");
    resource.setProperty(HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "NAMENODE");

    // request with an empty set should get all supported properties
    Request request = PropertyHelper.getReadRequest(Collections.<String>emptySet());

    Assert.assertEquals(1, propertyProvider.populateResources(Collections.singleton(resource), request, null).size());

    Assert.assertEquals(propertyProvider.getSpec("domu-12-31-39-0e-34-e1.compute-1.internal:50070"), streamProvider.getLastSpec());

    // see test/resources/hdfs_namenode_jmx.json for values
    Assert.assertEquals(13670605,  resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/rpc", "ReceivedBytes")));
    Assert.assertEquals(28,      resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/dfs/namenode", "CreateFileOps")));
    Assert.assertEquals(1006632960, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax")));
    Assert.assertEquals(473433016, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed")));
    Assert.assertEquals(136314880, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax")));
    Assert.assertEquals(23634400, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed")));


    // datanode
    resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domu-12-31-39-14-ee-b3.compute-1.internal");
    resource.setProperty(HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "DATANODE");

    // request with an empty set should get all supported properties
    request = PropertyHelper.getReadRequest(Collections.<String>emptySet());

    propertyProvider.populateResources(Collections.singleton(resource), request, null);

    Assert.assertEquals(propertyProvider.getSpec("domu-12-31-39-14-ee-b3.compute-1.internal:50075"), streamProvider.getLastSpec());

    // see test/resources/hdfs_datanode_jmx.json for values
    Assert.assertEquals(856,  resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/rpc", "ReceivedBytes")));
    Assert.assertEquals(954466304, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax")));
    Assert.assertEquals(9772616, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed")));
    Assert.assertEquals(136314880, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax")));
    Assert.assertEquals(21933376, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed")));


    // jobtracker
    resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domu-12-31-39-14-ee-b3.compute-1.internal");
    resource.setProperty(HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "JOBTRACKER");

    // only ask for specific properties
    Set<String> properties = new HashSet<String>();
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "threadsWaiting"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed"));
    request = PropertyHelper.getReadRequest(properties);

    propertyProvider.populateResources(Collections.singleton(resource), request, null);

    Assert.assertEquals(propertyProvider.getSpec("domu-12-31-39-14-ee-b3.compute-1.internal:50030"), streamProvider.getLastSpec());

    // see test/resources/mapreduce_jobtracker_jmx.json for values
    Assert.assertEquals(7, PropertyHelper.getProperties(resource).size());
    Assert.assertEquals(59, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "threadsWaiting")));
    Assert.assertEquals(1052770304, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax")));
    Assert.assertEquals(43580400, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed")));
    Assert.assertEquals(136314880, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax")));
    Assert.assertEquals(29602888, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed")));

    Assert.assertNull(resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "gcCount")));

    // tasktracker
    resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domu-12-31-39-14-ee-b3.compute-1.internal");
    resource.setProperty(HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "TASKTRACKER");

    // only ask for specific properties
    properties = new HashSet<String>();
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed"));
    request = PropertyHelper.getReadRequest(properties);

    propertyProvider.populateResources(Collections.singleton(resource), request, null);

    Assert.assertEquals(propertyProvider.getSpec("domu-12-31-39-14-ee-b3.compute-1.internal:50060"), streamProvider.getLastSpec());

    Assert.assertEquals(6, PropertyHelper.getProperties(resource).size());
    Assert.assertEquals(954466304, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax")));
    Assert.assertEquals(18330984, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed")));
    Assert.assertEquals(136314880, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax")));
    Assert.assertEquals(24235104, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed")));

    Assert.assertNull(resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "gcCount")));

    // hbase master
    resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domu-12-31-39-14-ee-b3.compute-1.internal");
    resource.setProperty(HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "HBASE_MASTER");

    // only ask for specific properties
    properties = new HashSet<String>();
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax"));
    properties.add(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed"));
    properties.add(PropertyHelper.getPropertyId("metrics/load", "AverageLoad"));
    request = PropertyHelper.getReadRequest(properties);

    propertyProvider.populateResources(Collections.singleton(resource), request, null);

    Assert.assertEquals(propertyProvider.getSpec("domu-12-31-39-14-ee-b3.compute-1.internal:60010"), streamProvider.getLastSpec());

    Assert.assertEquals(7, PropertyHelper.getProperties(resource).size());
    Assert.assertEquals(1069416448, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryMax")));
    Assert.assertEquals(4806976, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "HeapMemoryUsed")));
    Assert.assertEquals(136314880, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryMax")));
    Assert.assertEquals(28971240, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "NonHeapMemoryUsed")));
    Assert.assertEquals(3.0, resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/load", "AverageLoad")));

    Assert.assertNull(resource.getPropertyValue(PropertyHelper.getPropertyId("metrics/jvm", "gcCount")));
  }

  private static class TestJMXHostProvider implements JMXHostProvider {
    @Override
    public String getHostName(String clusterName, String componentName) {
      return null;
    }

    @Override
    public String getPort(String clusterName, String componentName) throws
      SystemException {
      if (componentName.equals("NAMENODE"))
        return "50070";
      else if (componentName.equals("DATANODE"))
        return "50075";
      else if (componentName.equals("JOBTRACKER"))
        return "50030";
      else if (componentName.equals("TASKTRACKER"))
        return "50060";
      else if (componentName.equals("HBASE_MASTER"))
        return "60010";
      else
        return null;
    }

  }
}

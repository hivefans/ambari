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

import org.apache.ambari.server.controller.internal.ResourceImpl;
import org.apache.ambari.server.controller.spi.PropertyId;
import org.apache.ambari.server.controller.spi.PropertyProvider;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.controller.utilities.StreamProvider;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * JMX property provider tests.
 */
public class JMXPropertyProviderTest {

  private static Map<String, String> HOST_MAPPING = new HashMap<String, String>();

  static {
    HOST_MAPPING.put("domU-12-31-39-0E-34-E1.compute-1.internal", "ec2-50-17-129-192.compute-1.amazonaws.com");
    HOST_MAPPING.put("ip-10-190-186-15.ec2.internal",             "ec2-23-21-8-226.compute-1.amazonaws.com");
    HOST_MAPPING.put("domU-12-31-39-14-EE-B3.compute-1.internal", "ec2-23-23-71-42.compute-1.amazonaws.com");
    HOST_MAPPING.put("ip-10-110-157-51.ec2.internal",             "ec2-107-22-121-67.compute-1.amazonaws.com");
  }

  private static Map<String, String> FILE_MAPPING = new HashMap<String, String>();

  static {
    FILE_MAPPING.put("http://ec2-23-21-8-226.compute-1.amazonaws.com:50070/jmx?qry=Hadoop:*", "hdfs_namenode_jmx.json");
    FILE_MAPPING.put("http://ec2-23-23-71-42.compute-1.amazonaws.com:50075/jmx?qry=Hadoop:*", "hdfs_datanode_jmx.json");
    FILE_MAPPING.put("http://ec2-23-23-71-42.compute-1.amazonaws.com:50030/jmx?qry=Hadoop:*", "mapreduce_jobtracker_jmx.json");
  }

  @Test
  public void testGetResources() throws Exception {

    Set< PropertyId >       propertyIds     = PropertyHelper.getPropertyIds(Resource.Type.HostComponent, "JMX");
    TestStreamProvider      streamProvider  = new TestStreamProvider();
    TestHostMappingProvider mappingProvider = new TestHostMappingProvider();

    PropertyProvider propertyProvider = new JMXPropertyProvider(propertyIds,
        streamProvider,
        mappingProvider);

    // namenode
    Resource resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(JMXPropertyProvider.HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "ip-10-190-186-15.ec2.internal");
    resource.setProperty(JMXPropertyProvider.HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "NAMENODE");

    // request with an empty set should get all supported properties
    Request request = PropertyHelper.getReadRequest(Collections.<PropertyId>emptySet());

    Assert.assertEquals(1, propertyProvider.populateResources(Collections.singleton(resource), request, null).size());

    Assert.assertEquals(JMXPropertyProvider.getSpec("ec2-23-21-8-226.compute-1.amazonaws.com:50070"), streamProvider.getLastSpec());

    // see test/resources/hdfs_namenode_jmx.json for values
    Assert.assertEquals("1084287",  resource.getPropertyValue(PropertyHelper.getPropertyId("ReceivedBytes", "rpc")));
    Assert.assertEquals("173",      resource.getPropertyValue(PropertyHelper.getPropertyId("CreateFileOps", "dfs")));
    Assert.assertEquals("405.8686", resource.getPropertyValue(PropertyHelper.getPropertyId("memHeapUsedM",  "jvm")));


    // datanode
    resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(JMXPropertyProvider.HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domU-12-31-39-14-EE-B3.compute-1.internal");
    resource.setProperty(JMXPropertyProvider.HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "DATANODE");

    // request with an empty set should get all supported properties
    request = PropertyHelper.getReadRequest(Collections.<PropertyId>emptySet());

    propertyProvider.populateResources(Collections.singleton(resource), request, null);

    Assert.assertEquals(JMXPropertyProvider.getSpec("ec2-23-23-71-42.compute-1.amazonaws.com:50075"), streamProvider.getLastSpec());

    // see test/resources/hdfs_datanode_jmx.json for values
    Assert.assertEquals("0",  resource.getPropertyValue(PropertyHelper.getPropertyId("ReceivedBytes", "rpc")));
    Assert.assertEquals("16.870667", resource.getPropertyValue(PropertyHelper.getPropertyId("memHeapUsedM",  "jvm")));


    // jobtracker
    resource = new ResourceImpl(Resource.Type.HostComponent);

    resource.setProperty(JMXPropertyProvider.HOST_COMPONENT_HOST_NAME_PROPERTY_ID, "domU-12-31-39-14-EE-B3.compute-1.internal");
    resource.setProperty(JMXPropertyProvider.HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID, "JOBTRACKER");

    // only ask for one property
    request = PropertyHelper.getReadRequest(Collections.singleton(PropertyHelper.getPropertyId("threadsWaiting", "jvm")));

    propertyProvider.populateResources(Collections.singleton(resource), request, null);

    Assert.assertEquals(JMXPropertyProvider.getSpec("ec2-23-23-71-42.compute-1.amazonaws.com:50030"), streamProvider.getLastSpec());

    // see test/resources/mapreduce_jobtracker_jmx.json for values
    // resource should now contain 3 properties... host name, component name, and jvm.threadsWaiting (from request)
    Assert.assertEquals(3, PropertyHelper.getProperties(resource).size());
    Assert.assertEquals("59", resource.getPropertyValue(PropertyHelper.getPropertyId("threadsWaiting", "jvm")));
    Assert.assertNull(resource.getPropertyValue(PropertyHelper.getPropertyId("gcCount", "jvm")));
  }


  public static class TestStreamProvider implements StreamProvider {
    private String lastSpec;

    @Override
    public InputStream readFrom(String spec) throws IOException {
      lastSpec = spec;
      String filename = FILE_MAPPING.get(spec);
      if (filename == null) {
        throw new IOException("Can't find JMX source for " + spec);
      }
      InputStream resourceAsStream = ClassLoader.getSystemResourceAsStream(filename);
      return resourceAsStream;
    }

    public String getLastSpec() {
      return lastSpec;
    }
  }

  public static class TestHostMappingProvider implements HostMappingProvider {
    @Override
    public Map<String, String> getHostMap() {
      return HOST_MAPPING;
    }
  }
}
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

package org.apache.ambari.server.controller.ganglia;

import org.apache.ambari.server.controller.internal.AbstractPropertyProvider;
import org.apache.ambari.server.controller.internal.PropertyInfo;
import org.apache.ambari.server.controller.spi.*;
import org.apache.ambari.server.controller.utilities.StreamProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Abstract property provider implementation for a Ganglia source.
 */
public abstract class GangliaPropertyProvider extends AbstractPropertyProvider {

  private final StreamProvider streamProvider;

  private final GangliaHostProvider hostProvider;

  private final String clusterNamePropertyId;

  private final String hostNamePropertyId;

  private final String componentNamePropertyId;

  /**
   * Map of Ganglia cluster names keyed by component type.
   */
  public static final Map<String, String> GANGLIA_CLUSTER_NAME_MAP = new HashMap<String, String>();

  static {
    GANGLIA_CLUSTER_NAME_MAP.put("NAMENODE", "HDPNameNode");
    GANGLIA_CLUSTER_NAME_MAP.put("DATANODE", "HDPSlaves");
    GANGLIA_CLUSTER_NAME_MAP.put("JOBTRACKER", "HDPJobTracker");
    GANGLIA_CLUSTER_NAME_MAP.put("TASKTRACKER", "HDPSlaves");
    GANGLIA_CLUSTER_NAME_MAP.put("HBASE_MASTER", "HDPHBaseMaster");
    GANGLIA_CLUSTER_NAME_MAP.put("HBASE_CLIENT", "HDPSlaves");
    GANGLIA_CLUSTER_NAME_MAP.put("HBASE_REGIONSERVER", "HDPSlaves");
  }

  protected final static Logger LOG =
      LoggerFactory.getLogger(GangliaPropertyProvider.class);

  // ----- Constructors ------------------------------------------------------

  public GangliaPropertyProvider(Map<String, Map<String, PropertyInfo>> componentPropertyInfoMap,
                                 StreamProvider streamProvider,
                                 GangliaHostProvider hostProvider,
                                 String clusterNamePropertyId,
                                 String hostNamePropertyId,
                                 String componentNamePropertyId) {

    super(componentPropertyInfoMap);

    this.streamProvider           = streamProvider;
    this.hostProvider             = hostProvider;
    this.clusterNamePropertyId    = clusterNamePropertyId;
    this.hostNamePropertyId       = hostNamePropertyId;
    this.componentNamePropertyId  = componentNamePropertyId;
  }


  // ----- PropertyProvider --------------------------------------------------

  @Override
  public Set<Resource> populateResources(Set<Resource> resources, Request request, Predicate predicate)
      throws SystemException {

    Set<String> ids = getRequestPropertyIds(request, predicate);
    if (ids.isEmpty()) {
      return resources;
    }

    Set<Resource> keepers = new HashSet<Resource>();

    Map<String, Map<TemporalInfo, RRDRequest>> requestMap = getRRDRequests(resources, request, ids);

    // For each cluster...
    for (Map.Entry<String, Map<TemporalInfo, RRDRequest>> clusterEntry : requestMap.entrySet()) {
      // For each request ...
      for (RRDRequest rrdRequest : clusterEntry.getValue().values() ) {
        //todo: property provider can reduce set of resources
        keepers.addAll(rrdRequest.populateResources());
      }
    }
    //todo: ignoring keepers returned by the provider
    return resources;
  }


  // ----- GangliaPropertyProvider -------------------------------------------

  /**
   * Get the host name for the given resource.
   *
   * @param resource  the resource
   *
   * @return the host name
   */
  protected abstract String getHostName(Resource resource);

  /**
   * Get the component name for the given resource.
   *
   * @param resource  the resource
   *
   * @return the component name
   */
  protected abstract String getComponentName(Resource resource);

  /**
   * Get the ganglia cluster name for the given resource.
   *
   *
   * @param resource  the resource
   *
   * @return the ganglia cluster name
   */
  protected abstract Set<String> getGangliaClusterNames(Resource resource, String clusterName);


  /**
   * Get the component name property id.
   *
   * @return the component name property id
   */
  protected String getComponentNamePropertyId() {
    return componentNamePropertyId;
  }

  /**
   * Get the host name property id.
   *
   * @return the host name property id
   */
  protected String getHostNamePropertyId() {
    return hostNamePropertyId;
  }

  /**
   * Get the stream provider.
   *
   * @return the stream provider
   */
  public StreamProvider getStreamProvider() {
    return streamProvider;
  }


  // ----- helper methods ----------------------------------------------------

  /**
   * Get the request objects containing all the information required to
   * make single requests to the Ganglia rrd script.
   * Requests are created per cluster name / temporal information but
   * can span multiple resources and metrics.
   *
   * @param resources  the resources being populated
   * @param request    the request
   * @param ids        the relevant property ids
   *
   * @return a map of maps of rrd requests keyed by cluster name / temporal info
   */
  private Map<String, Map<TemporalInfo, RRDRequest>> getRRDRequests(Set<Resource> resources,
                                                                    Request request,
                                                                    Set<String> ids) {

    Map<String, Map<TemporalInfo, RRDRequest>> requestMap =
        new HashMap<String, Map<TemporalInfo, RRDRequest>>();

    for (Resource resource : resources) {
      String clusterName = (String) resource.getPropertyValue(clusterNamePropertyId);
      Map<TemporalInfo, RRDRequest> requests = requestMap.get(clusterName);
      if (requests == null) {
        requests = new HashMap<TemporalInfo, RRDRequest>();
        requestMap.put(clusterName, requests);
      }

      Set<String> gangliaClusterNames = getGangliaClusterNames(resource, clusterName);

      for (String gangliaClusterName : gangliaClusterNames) {
        ResourceKey key =
            new ResourceKey(getHostName(resource), gangliaClusterName);

        for (String id : ids) {
          Map<String, PropertyInfo> propertyInfoMap = getPropertyInfoMap(getComponentName(resource), id);

          for (Map.Entry<String, PropertyInfo> entry : propertyInfoMap.entrySet()) {
            String propertyId = entry.getKey();
            PropertyInfo propertyInfo = entry.getValue();

            TemporalInfo temporalInfo = request.getTemporalInfo(id);

            if ((temporalInfo == null && propertyInfo.isPointInTime()) || (temporalInfo != null && propertyInfo.isTemporal())) {
              RRDRequest rrdRequest = requests.get(temporalInfo);
              if (rrdRequest == null) {
                rrdRequest = new RRDRequest(clusterName, temporalInfo);
                requests.put(temporalInfo, rrdRequest);
              }
              rrdRequest.putResource(key, resource);
              rrdRequest.putPropertyId(propertyInfo.getPropertyId(), propertyId);
            }
          }
        }
      }
    }
    return requestMap;
  }

  /**
   * Get the spec to locate the Ganglia stream from the given
   * request info.
   *
   * @param clusterName   the cluster name
   * @param clusterSet    the set of ganglia cluster names
   * @param hostSet       the set of host names
   * @param metricSet     the set of metric names
   * @param temporalInfo  the temporal information
   *
   * @return the spec
   *
   * @throws SystemException if unable to get the Ganglia Collector host name
   */
  private String getSpec(String clusterName,
                         Set<String> clusterSet,
                         Set<String> hostSet,
                         Set<String> metricSet,
                         TemporalInfo temporalInfo) throws SystemException {

    String clusters = getSetString(clusterSet, -1);
    String hosts    = getSetString(hostSet, -1);
    String metrics  = getSetString(metricSet, 50);

    StringBuilder sb = new StringBuilder();

    sb.append("http://").
        append(hostProvider.getGangliaCollectorHostName(clusterName)).
        append("/cgi-bin/rrd.py?c=").
        append(clusters);

    if (hosts.length() > 0) {
      sb.append("&h=").append(hosts);
    }

    if (metrics.length() > 0) {
      sb.append("&m=").append(metrics);
    }

    if (temporalInfo != null) {
      long startTime = temporalInfo.getStartTime();
      if (startTime != -1) {
        sb.append("&s=").append(startTime);
      }

      long endTime = temporalInfo.getEndTime();
      if (endTime != -1) {
        sb.append("&e=").append(endTime);
      }

      long step = temporalInfo.getStep();
      if (step != -1) {
        sb.append("&r=").append(step);
      }
    }
    else {
      sb.append("&e=now");
      sb.append("&pt=true");
    }

    return sb.toString();
  }

  /**
   * Get value from the given metric.
   *
   * @param metric      the metric
   * @param isTemporal  indicates whether or not this a temporal metric
   *
   * @return a range of temporal data or a point in time value if not temporal
   */
  private static Object getValue(GangliaMetric metric, boolean isTemporal) {
    Number[][] dataPoints = metric.getDatapoints();

    if (isTemporal) {
      return dataPoints;
    } else {
      // return the value of the last data point
      int length = dataPoints.length;
      return length > 0 ? dataPoints[length - 1][0] : 0;
    }
  }

  /**
   * Get a comma delimited string from the given set of strings or
   * an empty string if the size of the given set is greater than
   * the given limit.
   *
   * @param set    the set of strings
   * @param limit  the upper size limit for the list
   *
   * @return a comma delimited string of strings
   */
  private static String getSetString(Set<String> set, int limit) {
    StringBuilder sb = new StringBuilder();

    if (limit == -1 || set.size() <= limit) {
      for (String cluster : set) {
        if (sb.length() > 0) {
          sb.append(",");
        }
        sb.append(cluster);
      }
    }
    return sb.toString();
  }


  // ----- inner classes -----------------------------------------------------


  // ----- RRDRequest ----------------------------------------------------

  /**
   * The information required to make a single RRD request.
   */
  private class RRDRequest {
    private final String clusterName;
    private final TemporalInfo temporalInfo;
    private final Map<ResourceKey, Set<Resource>> resources = new HashMap<ResourceKey, Set<Resource>>();
    private final Map<String, Set<String>> metrics = new HashMap<String, Set<String>>();
    private final Set<String> clusterSet = new HashSet<String>();
    private final Set<String> hostSet = new HashSet<String>();


    private RRDRequest(String clusterName, TemporalInfo temporalInfo) {
      this.clusterName  = clusterName;
      this.temporalInfo = temporalInfo;
    }

    public void putResource(ResourceKey key, Resource resource) {
      clusterSet.add(key.getClusterName());
      hostSet.add(key.getHostName());
      Set<Resource> resourceSet = resources.get(key);
      if (resourceSet == null) {
        resourceSet = new HashSet<Resource>();
        resources.put(key, resourceSet);
      }
      resourceSet.add(resource);
    }

    public void putPropertyId(String metric, String id) {
      Set<String> propertyIds = metrics.get(metric);

      if (propertyIds == null) {
        propertyIds = new HashSet<String>();
        metrics.put(metric, propertyIds);
      }
      propertyIds.add(id);
    }

    /**
     * Populate the associated resources by making the rrd request.
     *
     * @return a collection of populated resources
     *
     * @throws SystemException if unable to populate the resources
     */
    public Collection<Resource> populateResources() throws SystemException {

      String spec = getSpec(clusterName, clusterSet, hostSet, metrics.keySet(), temporalInfo);
      BufferedReader reader = null;
      try {
        reader = new BufferedReader(new InputStreamReader(
            getStreamProvider().readFrom(spec)));

        int startTime = convertToNumber(reader.readLine()).intValue();

        String dsName = reader.readLine();
        while(! dsName.equals("[AMBARI_END]")) {
          GangliaMetric metric = new GangliaMetric();
          List<GangliaMetric.TemporalMetric> listTemporalMetrics =
              new ArrayList<GangliaMetric.TemporalMetric>();

          metric.setDs_name(dsName);
          metric.setCluster_name(reader.readLine());
          metric.setHost_name(reader.readLine());
          metric.setMetric_name(reader.readLine());

          int time = convertToNumber(reader.readLine()).intValue();
          int step = convertToNumber(reader.readLine()).intValue();

          String val = reader.readLine();
          while(! val.equals("[AMBARI_DP_END]")) {
            listTemporalMetrics.add(
                new GangliaMetric.TemporalMetric(convertToNumber(val), time));
            time += step;
            val = reader.readLine();
          }

          //todo: change setter in GangliaMetric to take collection
          Number[][] datapointsArray = new Number[listTemporalMetrics.size()][2];
          for (int i = 0; i < listTemporalMetrics.size(); ++i) {
            GangliaMetric.TemporalMetric m = listTemporalMetrics.get(i);
            datapointsArray[i][0] = m.getValue();
            datapointsArray[i][1] = m.getTime();
          }
          metric.setDatapoints(datapointsArray);

          ResourceKey key = new ResourceKey(metric.getHost_name(), metric.getCluster_name());
          Set<Resource> resourceSet = resources.get(key);
          if (resourceSet != null) {
            for (Resource resource : resourceSet) {
              populateResource(resource, metric);
            }
          }

          dsName = reader.readLine();
        }
        int endTime = convertToNumber(reader.readLine()).intValue();

        if (LOG.isInfoEnabled()) {
          LOG.info("Ganglia resource population time: " + (endTime - startTime));
        }
      } catch (IOException e) {
        if (LOG.isErrorEnabled()) {
          LOG.error("Caught exception getting Ganglia metrics : spec=" + spec, e);
        }
      } finally {
        if (reader != null) {
          try {
            reader.close();
          } catch (IOException e) {
            if (LOG.isWarnEnabled()) {
              LOG.warn("Unable to close http input steam : spec=" + spec, e);
            }
          }
        }
      }
      //todo: filter out resources and return keepers
      return Collections.emptySet();
    }

    /**
     * Populate the given resource with the given Ganglia metric.
     *
     * @param resource       the resource
     * @param gangliaMetric  the Ganglia metrics
     */
    private void populateResource(Resource resource, GangliaMetric gangliaMetric) {
      Set<String> propertyIdSet = metrics.get(gangliaMetric.getMetric_name());
      if (propertyIdSet != null) {
        Map<String, PropertyInfo> metricsMap = getComponentMetrics().get(getComponentName(resource));
        if (metricsMap != null) {
          for (String propertyId : propertyIdSet) {
            if (propertyId != null) {
              if (metricsMap.containsKey(propertyId)){
                resource.setProperty(propertyId, getValue(gangliaMetric, temporalInfo != null));
              }
            }
          }
        }
      }
    }

    private Number convertToNumber(String s) {
      return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
    }
  }


  // ----- ResourceKey ---------------------------------------------------

  /**
   * Key used to associate information from a Ganglia metric to a resource.
   */
  private static class ResourceKey {
    private final String hostName;
    private final String gangliaClusterName;

    private ResourceKey(String hostName, String gangliaClusterName) {
      this.hostName           = hostName;
      this.gangliaClusterName = gangliaClusterName;
    }

    public String getHostName() {
      return hostName;
    }

    public String getClusterName() {
      return gangliaClusterName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ResourceKey that = (ResourceKey) o;

      return
          !(gangliaClusterName != null ? !gangliaClusterName.equals(that.gangliaClusterName) : that.gangliaClusterName != null) &&
          !(hostName != null ? !hostName.equals(that.hostName) : that.hostName != null);

    }

    @Override
    public int hashCode() {
      int result = hostName != null ? hostName.hashCode() : 0;
      result = 31 * result + (gangliaClusterName != null ? gangliaClusterName.hashCode() : 0);
      return result;
    }
  }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

var App = require('app');

/**
 * @class
 * 
 * This is a view for showing cluster CPU metrics
 * 
 * @extends App.ChartLinearTimeView
 * @extends Ember.Object
 * @extends Ember.View
 */
App.ChartServiceMetricsMapReduce_MapSlots = App.ChartLinearTimeView.extend({
  id: "service-metrics-mapreduce-map-slots",
  title: Em.I18n.t('services.service.info.metrics.mapreduce.mapSlots'),
  renderer: 'line',
  sourceUrl: "/services/MAPREDUCE/components/JOBTRACKER?fields=metrics/mapred/jobtracker/occupied_map_slots[{fromSeconds},{toSeconds},{stepSeconds}],metrics/mapred/jobtracker/reserved_map_slots[{fromSeconds},{toSeconds},{stepSeconds}]",
  mockUrl: "/data/services/metrics/mapreduce/map_slots.json",

  transformToSeries: function (jsonData) {
    var seriesArray = [];
    if (jsonData && jsonData.metrics && jsonData.metrics.mapred && jsonData.metrics.mapred.jobtracker) {
      for ( var name in jsonData.metrics.mapred.jobtracker) {
        var displayName;
        var seriesData = jsonData.metrics.mapred.jobtracker[name];
        switch (name) {
          case "reserved_map_slots":
            displayName = "Map Slots Reserved";
            break;
          case "occupied_map_slots":
            displayName = "Map Slots Occupied";
            break;
          default:
            break;
        }
        if (seriesData) {
          seriesArray.push(this.transformData(seriesData, displayName));
        }
      }
    }
    return seriesArray;
  }
});
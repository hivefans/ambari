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
App.ChartServiceMetricsMapReduce_TasksRunningWaiting = App.ChartLinearTimeView.extend({
  id: "service-metrics-mapreduce-tasks-running-waiting",
  title: Em.I18n.t('services.service.info.metrics.mapreduce.tasksRunningWaiting'),
  renderer: 'line',
  sourceUrl: "/services/MAPREDUCE/components/JOBTRACKER?fields=metrics/mapred/jobtracker/running_maps[{fromSeconds},{toSeconds},{stepSeconds}],metrics/mapred/jobtracker/running_reduces[{fromSeconds},{toSeconds},{stepSeconds}],metrics/mapred/jobtracker/waiting_maps[{fromSeconds},{toSeconds},{stepSeconds}],metrics/mapred/jobtracker/waiting_reduces[{fromSeconds},{toSeconds},{stepSeconds}]",
  mockUrl: "/data/services/metrics/mapreduce/tasks_running_waiting.json",

  transformToSeries: function (jsonData) {
    var seriesArray = [];
    if (jsonData && jsonData.metrics && jsonData.metrics.mapred && jsonData.metrics.mapred.jobtracker) {
      for ( var name in jsonData.metrics.mapred.jobtracker) {
        var displayName;
        var seriesData = jsonData.metrics.mapred.jobtracker[name];
        switch (name) {
          case "running_maps":
            displayName = "Running Map Tasks";
            break;
          case "running_reduces":
            displayName = "Running Reduce Tasks";
            break;
          case "waiting_maps":
            displayName = "Waiting Map Tasks";
            break;
          case "waiting_reduces":
            displayName = "Waiting Reduce Tasks";
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
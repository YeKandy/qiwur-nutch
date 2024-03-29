/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.nutch.mapreduce;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.CounterGroup;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.nutch.api.model.request.JobConfig;
import org.apache.nutch.metadata.Nutch;

import com.google.common.collect.Maps;

public class NutchUtil {

  public static String generateBatchId() {
    return (System.currentTimeMillis() / 1000) + "-" + Math.abs(new Random().nextInt());
  }

  public static String generateConfigId() {
    return (System.currentTimeMillis() / 1000) + "-" + Math.abs(new Random().nextInt());
  }

  public static String generateJobId(JobConfig jobConfig, int hashCode) {
    if (jobConfig.getCrawlId() == null) {
      return MessageFormat.format("{0}-{1}-{2}", jobConfig.getConfId(),
          jobConfig.getType(), String.valueOf(hashCode));
    }

    return MessageFormat.format("{0}-{1}-{2}-{3}", jobConfig.getCrawlId(),
        jobConfig.getConfId(), jobConfig.getType(), String.valueOf(hashCode));
  }

  public static final Map<String, Object> toArgMap(Object... args) {
    if (args == null) {
      return null;
    }

    if (args.length % 2 != 0) {
      throw new RuntimeException("expected pairs of argName argValue");
    }

    HashMap<String, Object> res = new HashMap<String, Object>();
    for (int i = 0; i < args.length; i += 2) {
      if (args[i + 1] != null) {
        res.put(String.valueOf(args[i]), args[i + 1]);
      }
    }

    return res;
  }

  public static String printArgMap(Object... args) {
    return printArgMap(toArgMap(args));
  }

  public static String printArgMap(Map<String, Object> args) {
    StringBuilder sb = new StringBuilder();

    int i = 0;
    for (Entry<String, Object> arg : args.entrySet()) {
      if (i++ > 0) {
        sb.append(", ");
      }

      sb.append(arg.getKey());
      sb.append(" : ");
      sb.append(arg.getValue());
    }

    return sb.toString();
  }

  public static String get(Map<String, Object> args, String name) {
    return (String) args.get(name);
  }

  public static String get(Map<String, Object> args, String name, String defaultValue) {
    String value = (String) args.get(name);
    return value == null ? defaultValue : value;
  }

  public static Integer getInt(Map<String, Object> args, String name, int defaultValue) {
    Integer value = (Integer) args.get(name);
    return value == null ? defaultValue : value;
  }

  public static Long getLong(Map<String, Object> args, String name, long defaultValue) {
    Long value = (Long) args.get(name);
    return value == null ? defaultValue : value;
  }

  public static Boolean getBoolean(Map<String, Object> args, String name, boolean defaultValue) {
    Boolean value = (Boolean) args.get(name);
    return value == null ? defaultValue : value;
  }

  public static Map<String, Object> recordJobStatus(Job job) {
    Map<String, Object> jobStates = Maps.newHashMap();
    if (job == null) {
      return jobStates;
    }

    jobStates.putAll(getJobState(job, ArrayUtils.EMPTY_STRING_ARRAY));

    return jobStates;
  }

  public static Map<String, Object> getJobState(Job job, String... groups) {
    Map<String, Object> jobState = Maps.newHashMap();
    if (job == null) {
      return jobState;
    }

    try {
      if (job.getStatus() == null || job.isRetired()) {
        return jobState;
      }
    } catch (IOException | InterruptedException e) {
      return jobState;
    }

    jobState.put("jobName", job.getJobName());
    jobState.put("jobID", job.getJobID());

    jobState.put(Nutch.STAT_COUNTERS, getJobCounters(job, groups));

    return jobState;
  }

  public static Map<String, Object> getJobCounters(Job job, String... groups) {
    Map<String, Object> counters = Maps.newHashMap();
    if (job == null) {
      return counters;
    }

    try {
      for (CounterGroup group : job.getCounters()) {
        String groupName = group.getDisplayName();

        if (ArrayUtils.isEmpty(groups) || ArrayUtils.contains(groups, groupName)) {
          Map<String, Object> groupedCounters = Maps.newHashMap();
  
          for (Counter counter : group) {
            groupedCounters.put(counter.getName(), counter.getValue());
          }
  
          counters.put(groupName, groupedCounters);
        }
      }
    } catch (Exception e) {
      counters.put("error", e.toString());
    }

    return counters;
  }
}

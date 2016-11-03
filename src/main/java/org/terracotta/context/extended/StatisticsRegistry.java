/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.context.extended;

import org.terracotta.context.ContextManager;
import org.terracotta.context.TreeNode;
import org.terracotta.context.query.Matcher;
import org.terracotta.context.query.Matchers;
import org.terracotta.statistics.OperationStatistic;
import org.terracotta.statistics.Time;
import org.terracotta.statistics.ValueStatistic;
import org.terracotta.statistics.extended.CompoundOperation;
import org.terracotta.statistics.extended.CompoundOperationImpl;
import org.terracotta.statistics.extended.ExpiringSampledStatistic;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.terracotta.context.query.Matchers.attributes;
import static org.terracotta.context.query.Matchers.context;
import static org.terracotta.context.query.Matchers.hasAttribute;
import static org.terracotta.context.query.Matchers.identifier;
import static org.terracotta.context.query.Matchers.subclassOf;
import static org.terracotta.context.query.QueryBuilder.queryBuilder;

/**
 * @author Ludovic Orban
 */
public class StatisticsRegistry {

  private final Object contextObject;
  private final Map<String, RegisteredStatistic> registrations = new ConcurrentHashMap<String, RegisteredStatistic>();

  private final ScheduledExecutorService executor;
  private final Runnable disableTask;
  private volatile long timeToDisable;
  private volatile TimeUnit timeToDisableUnit;
  private volatile ScheduledFuture<?> disableStatus;

  private final long averageWindowDuration;
  private final TimeUnit averageWindowUnit;
  private final int historySize;
  private final long historyInterval;
  private final TimeUnit historyIntervalUnit;

  public StatisticsRegistry(Object contextObject, ScheduledExecutorService executor, long averageWindowDuration,
                            TimeUnit averageWindowUnit, int historySize, long historyInterval, TimeUnit historyIntervalUnit, long timeToDisable, TimeUnit timeToDisableUnit) {
    this.contextObject = contextObject;
    this.averageWindowDuration = averageWindowDuration;
    this.averageWindowUnit = averageWindowUnit;
    this.historySize = historySize;
    this.historyInterval = historyInterval;
    this.historyIntervalUnit = historyIntervalUnit;

    this.executor = executor;
    this.timeToDisable = timeToDisable;
    this.timeToDisableUnit = timeToDisableUnit;
    this.disableTask = createDisableTask();
    this.disableStatus = this.executor.scheduleAtFixedRate(disableTask, timeToDisable, timeToDisable, timeToDisableUnit);
  }

  private Runnable createDisableTask() {
    return new Runnable() {
      @Override
      public void run() {
        long expireThreshold = Time.absoluteTime() - timeToDisableUnit.toMillis(timeToDisable);
        for (RegisteredStatistic registeredStatistic : registrations.values()) {
          registeredStatistic.getSupport().expire(expireThreshold);
        }
      }
    };
  }

  public synchronized void setTimeToDisable(long time, TimeUnit unit) {
    timeToDisable = time;
    timeToDisableUnit = unit;
    if (disableStatus != null) {
      disableStatus.cancel(false);
      disableStatus = executor.scheduleAtFixedRate(disableTask, timeToDisable, timeToDisable,
          timeToDisableUnit);
    }
  }

  public synchronized void setAlwaysOn(boolean enabled) {
    if (enabled) {
      if (disableStatus != null) {
        disableStatus.cancel(false);
        disableStatus = null;
      }
      for (RegisteredStatistic registeredStatistic : registrations.values()) {
        registeredStatistic.getSupport().setAlwaysOn(true);
      }
    } else {
      if (disableStatus == null) {
        disableStatus = executor.scheduleAtFixedRate(disableTask, 0, timeToDisable,
            timeToDisableUnit);
      }
      for (RegisteredStatistic registeredStatistic : registrations.values()) {
        registeredStatistic.getSupport().setAlwaysOn(false);
      }
    }
  }

  public void registerSize(String name, ValueStatisticDescriptor descriptor) {
    registerStatistic(name, descriptor, new Function<ExpiringSampledStatistic, RegisteredStatistic>() {
      @Override
      public RegisteredStatistic apply(ExpiringSampledStatistic expiringSampledStatistic) {
        return new RegisteredSizeStatistic(expiringSampledStatistic);
      }
    });
  }

  public void registerCounter(String name, ValueStatisticDescriptor descriptor) {
    registerStatistic(name, descriptor, new Function<ExpiringSampledStatistic, RegisteredStatistic>() {
      @Override
      public RegisteredCounterStatistic apply(ExpiringSampledStatistic expiringSampledStatistic) {
        return new RegisteredCounterStatistic(expiringSampledStatistic);
      }
    });
  }

  private <R extends RegisteredStatistic> void registerStatistic(String name, ValueStatisticDescriptor descriptor, Function<ExpiringSampledStatistic, R> registeredStatisticFunction) {
    Map<String, R> registeredStatistics = new HashMap<String, R>();

    Map<String, ValueStatistic<?>> valueStatistics = findValueStatistics(contextObject, name, descriptor.getObserverName(), descriptor.getTags());
    Set<String> duplicates = new HashSet<String>();
    for (Map.Entry<String, ValueStatistic<?>> entry : valueStatistics.entrySet()) {
      String key = entry.getKey();
      ValueStatistic<?> value = entry.getValue();
      if (registrations.containsKey(key)) {
        duplicates.add(key);
      }
      ExpiringSampledStatistic expiringSampledStatistic = new ExpiringSampledStatistic(value, executor, historySize, historyInterval, historyIntervalUnit);
      R registeredStatistic = registeredStatisticFunction.apply(expiringSampledStatistic);
      registeredStatistics.put(key, registeredStatistic);
    }
    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException("Found duplicate value statistic(s) " + duplicates);
    }

    registrations.putAll(registeredStatistics);
  }

  public void registerCompoundOperations(String name, OperationStatisticDescriptor descriptor, EnumSet<?> compound) {
    Map<String, CompoundOperation<?>> compoundOperations = createCompoundOperations(name, descriptor.getObserverName(), descriptor.getTags(), descriptor.getType());

    Map<String, RegisteredCompoundOperationStatistic> registeredStatistics = new HashMap<String, RegisteredCompoundOperationStatistic>();
    Set<String> duplicates = new HashSet<String>();
    for (Map.Entry<String, CompoundOperation<?>> entry : compoundOperations.entrySet()) {
      String key = entry.getKey();
      if (registrations.containsKey(key)) {
        duplicates.add(key);
      }
      registeredStatistics.put(key, new RegisteredCompoundStatistic(entry.getValue(), compound));
    }
    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException("Found duplicate operation statistic(s) " + duplicates);
    }

    for (CompoundOperation<?> compoundOperation : compoundOperations.values()) {
      compoundOperation.compound((Set) compound);
    }
    registrations.putAll(registeredStatistics);
  }

  public void registerRatios(String name, OperationStatisticDescriptor descriptor, EnumSet<?> ratioNumerator, EnumSet<?> ratioDenominator) {
    Map<String, CompoundOperation<?>> compoundOperations = createCompoundOperations(name, descriptor.getObserverName(), descriptor.getTags(), descriptor.getType());

    Map<String, RegisteredCompoundOperationStatistic> registeredStatistics = new HashMap<String, RegisteredCompoundOperationStatistic>();
    Set<String> duplicates = new HashSet<String>();
    for (Map.Entry<String, CompoundOperation<?>> entry : compoundOperations.entrySet()) {
      String key = entry.getKey();
      if (registrations.containsKey(key)) {
        duplicates.add(key);
      }
      registeredStatistics.put(key, new RegisteredRatioStatistic(entry.getValue(), ratioNumerator, ratioDenominator));
    }
    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException("Found duplicate operation statistic(s) " + duplicates);
    }

    for (CompoundOperation<?> compoundOperation : compoundOperations.values()) {
      compoundOperation.ratioOf((Set) ratioNumerator, (Set) ratioDenominator);
    }
    registrations.putAll(registeredStatistics);
  }

  public Map<String, RegisteredStatistic> getRegistrations() {
    return Collections.unmodifiableMap(registrations);
  }

  public void clearRegistrations() {
    registrations.clear();
  }


  @SuppressWarnings({"unchecked", "rawtypes"})
  private Map<String, CompoundOperation<?>> createCompoundOperations(String name, String observerName, Set<String> tags, Class<? extends Enum<?>> type) {
    Map<String, CompoundOperation<?>> result = new HashMap<String, CompoundOperation<?>>();

    Map<String, OperationStatistic<?>> operationObservers = findOperationStatistics(contextObject, name, type, observerName, tags);
    if (operationObservers.isEmpty()) {
      throw new IllegalArgumentException("Required statistic observer '" + observerName + "' with tags " + tags + " and type '" + type + "' not found under '" + contextObject + "'");
    }

    for (Map.Entry<String, OperationStatistic<?>> entry : operationObservers.entrySet()) {
      CompoundOperation<?> newOperation = new CompoundOperationImpl(entry.getValue(), type,
          averageWindowDuration, averageWindowUnit, executor, historySize,
          historyInterval, historyIntervalUnit);
      result.put(entry.getKey(), newOperation);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, OperationStatistic<?>> findOperationStatistics(Object contextObject, String name, Class<? extends Enum<?>> type, String observerName, final Set<String> tags) {
    Set<TreeNode> result = queryBuilder()
        .descendants()
        .filter(context(attributes(Matchers.<Map<String, Object>>allOf(
            hasAttribute("type", type),
            hasAttribute("name", observerName),
            hasAttribute("tags", new Matcher<Set<String>>() {
              @Override
              protected boolean matchesSafely(Set<String> object) {
                return object.containsAll(tags);
              }
            })))))
        .filter(context(identifier(subclassOf(OperationStatistic.class))))
        .build().execute(Collections.singleton(ContextManager.nodeFor(contextObject)));

    if (result.isEmpty()) {
      return Collections.emptyMap();
    } else {
      Map<String, OperationStatistic<?>> observers = new HashMap<String, OperationStatistic<?>>();
      for (TreeNode node : result) {
        String discriminator = null;

        Map<String, Object> properties = (Map<String, Object>) node.getContext().attributes().get("properties");
        if (properties != null && properties.containsKey("discriminator")) {
          discriminator = properties.get("discriminator").toString();
        }

        String completeName = (discriminator == null ? "" : (discriminator + ":")) + name;
        OperationStatistic<?> existing = observers.put(completeName, (OperationStatistic<?>) node.getContext().attributes().get("this"));
        if (existing != null) {
          throw new IllegalStateException("Duplicate OperationStatistic found for '" + completeName + "'");
        }
      }
      return observers;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ValueStatistic<?>> findValueStatistics(Object contextObject, String name, String observerName, final Set<String> tags) {
    Set<TreeNode> result = queryBuilder()
        .descendants()
        .filter(context(attributes(Matchers.<Map<String, Object>>allOf(
            hasAttribute("name", observerName),
            hasAttribute("tags", new Matcher<Set<String>>() {
              @Override
              protected boolean matchesSafely(Set<String> object) {
                return object.containsAll(tags);
              }
            })))))
        .filter(context(identifier(subclassOf(ValueStatistic.class))))
        .build().execute(Collections.singleton(ContextManager.nodeFor(contextObject)));

    if (result.isEmpty()) {
      return Collections.emptyMap();
    } else {
      Map<String, ValueStatistic<?>> observers = new HashMap<String, ValueStatistic<?>>();
      for (TreeNode node : result) {
        String discriminator = null;

        Map<String, Object> properties = (Map<String, Object>) node.getContext().attributes().get("properties");
        if (properties != null && properties.containsKey("discriminator")) {
          discriminator = properties.get("discriminator").toString();
        }

        String completeName = (discriminator == null ? "" : (discriminator + ":")) + name;
        ValueStatistic<?> existing = observers.put(completeName, (ValueStatistic<?>) node.getContext().attributes().get("this"));
        if (existing != null) {
          throw new IllegalStateException("Duplicate ValueStatistic found for '" + completeName + "'");
        }
      }
      return observers;
    }
  }

  private interface Function<T, R> {
    R apply(T t);
  }

}
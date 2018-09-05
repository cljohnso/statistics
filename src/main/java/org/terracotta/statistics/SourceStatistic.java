/*
 * All content copyright Terracotta, Inc., unless otherwise indicated.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.statistics;

import org.terracotta.statistics.observer.ChainedObserver;

import java.util.Collection;

/**
 * Source statistic implementations support derived statistics.
 * <p>
 * Derived statistics can be registered and will then receive the relevant
 * observer calls to update their status.
 *
 * @param <T> Supported derived observer type
 */
public interface SourceStatistic<T extends ChainedObserver> {

  /**
   * Register the given {@code Observer} to be called by this {@code SourceStatistic}
   *
   * @param derived statistic to be registered
   */
  void addDerivedStatistic(T derived);

  /**
   * Remove the given registered {@code Observer} from this {@code SourceStatistic}.
   *
   * @param derived statistic to be removed
   */
  void removeDerivedStatistic(T derived);

  /**
   * Retrieve all registered statistics.
   *
   * @return an unmodifiable collection of all derived statistics
   */
  Collection<T> getDerivedStatistics();
}

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
package org.ehcache.transactions.xa;

import org.ehcache.exceptions.RethrowingCacheAccessException;

/**
 * A {@link org.ehcache.exceptions.CacheAccessException} thrown by the {@link XAStore} that is not handled by the
 * {@link org.ehcache.resilience.ResilienceStrategy} but used to throw a {@link RuntimeException} to the user of the cache.
 *
 * @author Ludovic Orban
 */
public class XACacheAccessException extends RethrowingCacheAccessException {
  public XACacheAccessException(RuntimeException cause) {
    super(cause);
  }
}

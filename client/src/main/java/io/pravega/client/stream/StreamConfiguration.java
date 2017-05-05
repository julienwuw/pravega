/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries.
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
package io.pravega.client.stream;

import java.io.Serializable;

import lombok.Builder;
import lombok.Data;

/**
 * The configuration of a Stream.
 */
@Data
@Builder
public class StreamConfiguration implements Serializable {
    
    /**
     * API to return scope.
     *
     */
    private final String scope;

    /**
     * API to return stream name.
     *
     */
    private final String streamName;

    /**
     * API to return scaling policy.
     *
     */
    private final ScalingPolicy scalingPolicy;

    /**
     * API to return retention policy.
     *
     */
    private final RetentionPolicy retentionPolicy;

    public static final class StreamConfigurationBuilder {
        private ScalingPolicy scalingPolicy = ScalingPolicy.fixed(1);
    }
}
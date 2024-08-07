/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr.cfg

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.grpc.EventStatus.FAILED
import com.exactpro.th2.common.grpc.EventStatus.SUCCESS
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategyConfiguration
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.util.concurrent.TimeUnit

class EventProcessorCfg(
    /**
     * The format for issue to process. The if event name matches the format
     * the issue key will be extracted according to that format.
     */
    issueFormat: String,

    /**
     * The name of the JIRA connection that should be used to synchronize the test events
     */
    val destination: String = ConnectionCfg.DEFAULT_NAME,

    /**
     * Delimiter for version and cycle name in the root event
     */
    val delimiter: Char = '|',
    /**
     * Mapping between event status and the status in Jira by its name
     */
    val statusMapping: Map<EventStatus, String>,

    /**
     * Contains mapping between folder and issues' keys that correspond to that folder
     */
    val folders: Map<String, Set<String>> = emptyMap(),
    /**
     * Mapping between version and cycle, and issues that correspond to those values
     */
    @JsonDeserialize(converter = DefaultIssueForVersionAndCycleConverter::class)
    val defaultCycleAndVersions: Map<VersionCycleKey, Set<String>> = emptyMap(),
    /**
     * Time in milliseconds to await until zephyr job is done.
     */
    val jobAwaitTimeout: Long = TimeUnit.SECONDS.toMillis(1),

    /**
     * The list of strategies to find the related issues
     */
    val relatedIssuesStrategies: List<RelatedIssuesStrategyConfiguration> = emptyList(),

    /**
     * Defines how the processor should interact with execution. By default, it tries to update the last one (if it is possible).
     * You can change the mode to [TestExecutionMode.CREATE_NEW] to change the behavior (if it is supported by zephyr)
     */
    val testExecutionMode: TestExecutionMode = TestExecutionMode.UPDATE_LAST,

    /**
     * The regular expression that is used to match the version inside event with cycle information.
     * This option is only applicable for [SCALE_SERVER][com.exactpro.th2.dataprocessor.zephyr.cfg.ZephyrType.SCALE_SERVER] Zephyr service.
     * Has no effect for [SQUAD][com.exactpro.th2.dataprocessor.zephyr.cfg.ZephyrType.SQUAD] type.
     */
    val versionPattern: String? = null,

    /**
     * Mapping between custom field to set in execution and the value it should have
     */
    val customFields: Map<String, CustomFieldExtraction> = emptyMap(),

    /**
     * Configuration that will be applied to the internal caches inside the zephyr processors (e.g. cycles cache)
     */
    val cachesConfiguration: CachesConfiguration = CachesConfiguration(),
) {
    val issueRegexp: Regex = issueFormat.toPattern().toRegex()
    init {
        require(jobAwaitTimeout > 0) { "jobAwaitTimeout must be grater than 0" }
        require(statusMapping.containsKey(FAILED)) { "mapping for $FAILED status must be set" }
        require(statusMapping.containsKey(SUCCESS)) { "mapping for $SUCCESS status must be set" }
        versionPattern?.runCatching { toPattern() }
            ?.onFailure { throw IllegalArgumentException("pattern $versionPattern cannot be converted to regexp", it) }
    }
}

enum class TestExecutionMode {
    UPDATE_LAST, CREATE_NEW
}

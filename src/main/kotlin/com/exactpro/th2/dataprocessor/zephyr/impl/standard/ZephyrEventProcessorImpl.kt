/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr.impl.standard

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprocessor.zephyr.RelatedIssuesStrategiesStorage
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.VersionCycleKey
import com.exactpro.th2.dataprocessor.zephyr.grpc.impl.getEventSuspend
import com.exactpro.th2.dataprocessor.zephyr.impl.AbstractZephyrProcessor
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.extensions.findVersion
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.ZephyrApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.BaseExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.Folder
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.ZephyrJob
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.request.Execution
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.request.ExecutionUpdate
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.util.EnumMap

class ZephyrEventProcessorImpl(
    private val configurations: List<EventProcessorCfg>,
    private val connections: Map<String, StandardServiceHolder>,
    dataProvider: AsyncDataProviderService,
    private val strategies: RelatedIssuesStrategiesStorage,
) : AbstractZephyrProcessor<ZephyrApiService>(configurations, connections, dataProvider) {
    constructor(
        configuration: EventProcessorCfg,
        connections: Map<String, StandardServiceHolder>,
        dataProvider: AsyncDataProviderService,
        knownStrategies: RelatedIssuesStrategiesStorage,
    ) : this(listOf(configuration), connections, dataProvider, knownStrategies)

    private val statusMapping: Map<String, Map<EventStatus, BaseExecutionStatus>> = runBlocking {
        configurations.associate { cfg ->
            val services: StandardServiceHolder = requireNotNull(connections[cfg.destination]) { "not connection with name ${cfg.destination}" }
            LOGGER.info { "Requesting statuses for connection named ${cfg.destination}" }
            val statuses = services.zephyr.getExecutionStatuses().associateBy { it.name }
            val mapping = EnumMap<EventStatus, BaseExecutionStatus>(EventStatus::class.java).apply {
                cfg.statusMapping.forEach { (eventStatus, zephyrStatusName) ->
                    put(eventStatus, requireNotNull(statuses[zephyrStatusName]) {
                        "Cannot find status $zephyrStatusName in Zephyr from connection ${cfg.destination}. Known statuses: ${statuses.values}"
                    })
                }
            }
            cfg.destination to mapping
        }
    }

    override suspend fun EventProcessorContext<ZephyrApiService>.processEvent(
        eventName: String,
        event: EventResponse,
        eventStatus: EventStatus
    ) {
        val executionStatus: BaseExecutionStatus = getExecutionStatusForEvent(configuration.destination, eventStatus)
        processEvent(eventName, event, executionStatus)
    }

    private fun getExecutionStatusForEvent(
        connectionName: String,
        eventStatus: EventStatus
    ): BaseExecutionStatus {
        return checkNotNull(statusMapping[connectionName]?.get(eventStatus)) {
            "Cannot find the status mapping for $eventStatus"
        }
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.processEvent(eventName: String, event: EventResponse, executionStatus: BaseExecutionStatus) {
        LOGGER.trace { "Getting information project and versions for event ${event.shortString}" }
        val issue: Issue = getIssue(eventName)
        val rootEvent: EventResponse? = findRootEvent(event)
        val folderEvent: EventResponse? = if (event.hasParentEventId() && event.parentEventId != rootEvent?.eventId) {
            dataProvider.getEventSuspend(event.parentEventId)
        } else {
            null
        }
        val folderName: String? = folderEvent?.eventName ?: configuration.folders.asSequence()
            .filter { it.value.contains(issue.key) }
            .map { it.key }
            .firstOrNull()
        val versionCycleKey: VersionCycleKey = extractVersionCycleKey(rootEvent, issue)

        updateOrCreateExecution(event, issue, versionCycleKey, folderName, executionStatus)

        configuration.relatedIssuesStrategies.forEach {
            val strategy = strategies[it]
            LOGGER.info { "Extracting related issues with strategy ${strategy::class.java.canonicalName}" }
            strategy.findRelatedFor(services, issue).forEach { relatedIssue ->
                updateOrCreateExecution(event, relatedIssue, versionCycleKey, folderName, executionStatus)
            }
        }
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.updateOrCreateExecution(
        event: EventResponse,
        issue: Issue,
        versionCycleKey: VersionCycleKey,
        folderName: String?,
        executionStatus: BaseExecutionStatus
    ) {
        val project: Project = getProject(issue)
        val (cycleName: String, version: Version) = with(versionCycleKey) {
            cycle to checkNotNull(project.findVersion(version)) {
                "Cannot find version $version for project ${project.name}"
            }
        }

        LOGGER.trace { "Getting cycle $cycleName for event ${event.shortString}" }
        val cycle: Cycle = getOrCreateCycle(cycleName, project, version)

        val folder: Folder? = folderName?.let {
            LOGGER.trace { "Getting folder $it for event ${event.shortString}" }
            getOrCreateFolderIfNeeded(cycle, it)
        }

        LOGGER.trace { "Getting execution for event ${event.shortString}" }
        val execution = getOrCreateExecution(project, version, cycle, folder, issue)
        checkNotNull(execution) { "Cannot find and create the execution for test ${issue.key} in project ${project.name}, version ${version.name}" }

        LOGGER.debug { "Updating execution for event ${event.shortString} with status $executionStatus" }
        zephyr.updateExecution(
            ExecutionUpdate(
                id = execution.id,
                status = executionStatus,
                comment = "Updated by th2 because of event with id: ${event.eventId.id}"
            )
        )
    }

    private suspend fun findRootEvent(event: EventResponse): EventResponse? = event.findRoot()

    private suspend fun EventProcessorContext<ZephyrApiService>.getOrCreateExecution(
        project: Project,
        version: Version,
        cycle: Cycle,
        folder: Folder?,
        issue: Issue
    ): Execution? {
        return zephyr.findExecution(project, version, cycle, folder, issue) ?: run {
            val job = if (folder == null) {
                addTestToCycle(issue, cycle)
            } else {
                addTestToFolder(issue, folder)
            }
            withTimeout(configuration.jobAwaitTimeout) {
                zephyr.awaitJobDone(job)
            }
            zephyr.findExecution(project, version, cycle, folder, issue)
        }
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.addTestToFolder(issue: Issue, folder: Folder): ZephyrJob {
        LOGGER.debug { "Adding the test ${issue.key} to folder ${folder.name}" }
        return zephyr.addTestToFolder(folder, issue)
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.addTestToCycle(issue: Issue, cycle: Cycle): ZephyrJob {
        LOGGER.debug { "Adding the test ${issue.key} to cycle ${cycle.name}" }
        return zephyr.addTestToCycle(cycle, issue)
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.getProject(issue: Issue): Project {
        return jira.projectByKey(issue.projectKey)
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.getOrCreateCycle(cycleName: String, project: Project, version: Version): Cycle {
        return zephyr.getCycle(cycleName, project, version) ?: run {
            LOGGER.debug { "Crating cycle $cycleName for project ${project.name} version ${version.name}" }
            zephyr.createCycle(cycleName, project, version)
        }
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.getIssue(eventName: String): Issue {
        return jira.issueByKey(eventName.toIssueKey())
    }

    private fun EventProcessorContext<ZephyrApiService>.extractVersionCycleKey(
        parent: EventResponse?,
        issue: Issue,
    ): VersionCycleKey {
        return if (parent == null) {
            getCycleNameAndVersionFromCfg(issue.key)
        } else {
            val split = parent.eventName.split(configuration.delimiter)
            check(split.size >= 2) { "The parent event's name ${parent.shortString} has incorrect format" }
            val (version: String, cycleName: String) = split
            VersionCycleKey(version, cycleName)
        }
    }

    private suspend fun EventProcessorContext<ZephyrApiService>.getOrCreateFolderIfNeeded(cycle: Cycle, folderName: String): Folder {
        return zephyr.getFolder(cycle, folderName) ?: run {
            LOGGER.debug { "Creating folder $folderName for cycle ${cycle.name}" }
            zephyr.createFolder(cycle, folderName)
        }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
        private val EventResponse.shortString: String
            get() = "id: ${eventId.toJson()}; name: $eventName"
    }
}

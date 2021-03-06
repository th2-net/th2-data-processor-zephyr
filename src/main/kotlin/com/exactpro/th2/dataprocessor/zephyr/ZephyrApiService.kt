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

package com.exactpro.th2.dataprocessor.zephyr

import com.exactpro.th2.dataprocessor.zephyr.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.model.Execution
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionRequest
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionResponse
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionUpdate
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionUpdateResponse
import com.exactpro.th2.dataprocessor.zephyr.model.Folder
import com.exactpro.th2.dataprocessor.zephyr.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.model.JobResult
import com.exactpro.th2.dataprocessor.zephyr.model.JobToken
import com.exactpro.th2.dataprocessor.zephyr.model.JobType
import com.exactpro.th2.dataprocessor.zephyr.model.Project
import com.exactpro.th2.dataprocessor.zephyr.model.Version
import com.exactpro.th2.dataprocessor.zephyr.model.ZephyrJob

interface ZephyrApiService : AutoCloseable {
    suspend fun getCycle(cycleName: String, project: Project, version: Version): Cycle?
    suspend fun createCycle(cycleName: String, project: Project, version: Version): Cycle

    suspend fun getExecutionStatuses(): List<ExecutionStatus>
    suspend fun createExecution(request: ExecutionRequest): ExecutionResponse
    suspend fun updateExecution(update: ExecutionUpdate): ExecutionUpdateResponse
    suspend fun addTestToCycle(cycle: Cycle, test: Issue): ZephyrJob
    suspend fun addTestToFolder(folder: Folder, test: Issue): ZephyrJob
    suspend fun awaitJobDone(job: ZephyrJob)
    suspend fun findExecution(project: Project, version: Version, cycle: Cycle, folder: Folder?, test: Issue): Execution?

    suspend fun getFolder(cycle: Cycle, folderName: String): Folder?
    suspend fun createFolder(cycle: Cycle, folderName: String): Folder
}

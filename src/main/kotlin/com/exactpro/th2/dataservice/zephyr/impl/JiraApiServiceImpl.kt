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

package com.exactpro.th2.dataservice.zephyr.impl

import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory
import com.exactpro.th2.dataservice.zephyr.JiraApiService
import com.exactpro.th2.dataservice.zephyr.Jql
import com.exactpro.th2.dataservice.zephyr.SearchParameters
import com.exactpro.th2.dataservice.zephyr.cfg.BaseAuth
import com.exactpro.th2.dataservice.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataservice.zephyr.model.AccountInfo
import com.exactpro.th2.dataservice.zephyr.model.Issue
import com.exactpro.th2.dataservice.zephyr.model.Project
import com.exactpro.th2.dataservice.zephyr.model.Version
import io.atlassian.util.concurrent.Promise
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.Json
import io.ktor.client.features.logging.LogLevel
import io.ktor.client.features.logging.Logging
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.coroutines.suspendCancellableCoroutine
import mu.KotlinLogging
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.ws.rs.core.UriBuilder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class JiraApiServiceImpl(
    private val uri: String,
    private val auth: BaseAuth,
    private val httpLogging: HttpLoggingConfiguration
) : JiraApiService {

    private val api = AsynchronousJiraRestClientFactory()
        .createWithBasicHttpAuthentication(URI.create(uri), auth.username, auth.key)
    private val httpClient = HttpClient(Java) {
        Auth {
            basic {
                this.username = auth.username
                this.password = auth.key
                sendWithoutRequest = true // send in first request instead of sending it after getting 401 response
            }
        }
        Json {
            serializer = JacksonSerializer()
        }
        Logging {
            level = httpLogging.level
        }
    }

    override suspend fun accountInfo(): AccountInfo {
        LOGGER.trace { "Getting account info for current user" }
        return httpClient.get(
            URLBuilder().takeFrom(uri).apply { path(encodedPath, REST_API_PREFIX, "myself") }.build()
        )
    }

    override suspend fun projectByKey(projectKey: String): Project {
        check(projectKey.isNotBlank()) { "project key cannot be blank" }
        LOGGER.trace { "Finding project with key '$projectKey'" }
        return api.projectClient.getProject(projectKey).await()
            .run { Project(id, key, name, versions.map { Version(it.id, it.name) }) }
    }

    override suspend fun issueByKey(issueKey: String): Issue {
        check(issueKey.isNotBlank()) { "issue key cannot be blank" }
        LOGGER.trace { "Finding issue with key '$issueKey'" }
        return api.issueClient.getIssue(issueKey).await()
            .run { toIssueModel() }
    }

    override suspend fun search(jql: Jql, searchParameters: SearchParameters?): List<Issue> {
        require(jql.isNotBlank()) { "'jql' cannot be blank" }
        LOGGER.trace { "Executing query $jql" + (searchParameters?.let { "; parameters $it" } ?: "") }
        return api.searchClient.run {
            if (searchParameters == null) {
                searchJql(jql)
            } else {
                with(searchParameters) {
                    searchJql(jql, limit, startAt, null)
                }
            }
        }.await()
            .run { issues.map { it.toIssueModel() } }
    }

    override fun close() {
        LOGGER.info { "Disposing resources for Jira service" }
        runCatching { api.close() }
            .onFailure { LOGGER.error(it) { "Cannot close Jira REST client" } }
        runCatching { httpClient.close() }
            .onFailure { LOGGER.error(it) { "Cannot close HTTP client" } }
    }


    companion object {
        private const val REST_API_PREFIX = "rest/api/latest"
        private val LOGGER = KotlinLogging.logger { }

        private fun com.atlassian.jira.rest.client.api.domain.Issue.toIssueModel() = Issue(id, key, project.key)

        private suspend fun <T> Promise<T>.await(): T = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { this.cancel(true) }
            done { cont.resume(it) }
                .fail { cont.resumeWithException(it) }
        }
    }
}
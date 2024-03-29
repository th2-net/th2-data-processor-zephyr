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

package com.exactpro.th2.dataprocessor.zephyr.service.api.model

data class Project(
    val id: Long,
    val key: String,
    val name: String?,
    val versions: List<Version>
) {
    constructor(id: Long?, key: String, name: String?, versions: List<Version>): this(id ?: -1, key, name, versions)
}

data class Version(
    val id: Long,
    val name: String
) {
    constructor(id: Long?, name: String?) : this(id ?: -1, name ?: "unknown version name")
}

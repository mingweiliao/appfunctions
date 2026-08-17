/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.appfunctions.agent.domain.appfunction

import android.app.AppInteractionAttribution
import android.app.AppInteractionManager
import android.app.AppInteractionSession
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.ExecuteAppFunctionResponse.Success.Companion.PROPERTY_RETURN_VALUE
import androidx.appfunctions.ExecuteAppFunctionResponse.Success.Companion.toCompatExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.core.net.toUri
import androidx.core.os.asOutcomeReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

/** Use case to execute an AppFunction. */
@Singleton
class ExecuteAppFunctionUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appFunctionManager: AppFunctionManager?,
        private val convertAppFunctionDataToJsonUseCase: ConvertAppFunctionDataToJsonUseCase,
    ) {
        private val availableSession = ConcurrentMap<String, AppInteractionSession>()

        /**
         * Executes the use case.
         *
         * @param function The metadata of the function to execute.
         * @param parameters The input parameters for the function.
         * @return A [ExecuteAppFunctionResult].
         */
        suspend operator fun invoke(
            function: AppFunctionMetadata,
            parameters: AppFunctionData,
            threadId: String? = null,
        ): ExecuteAppFunctionResult {
            if (appFunctionManager == null) {
                return ExecuteAppFunctionResult.Error(
                    IllegalStateException("AppFunctionManager not available on this device"),
                )
            }

            val request =
                if (Build.VERSION.SDK_INT >= 37) {
                    val uri =
                        if (threadId != null) {
                            "appfunctions-agent://chat?threadId=$threadId".toUri()
                        } else {
                            "appfunctions-agent://chat".toUri()
                        }
                    ExecuteAppFunctionRequest(
                        targetPackageName = function.packageName,
                        functionIdentifier = function.id,
                        functionParameters = parameters,
                        attribution =
                            AppInteractionAttribution.Builder(
                                AppInteractionAttribution.INTERACTION_TYPE_USER_QUERY,
                            )
                                .setInteractionUri(uri)
                                .build(),
                    )
                } else {
                    ExecuteAppFunctionRequest(
                        targetPackageName = function.packageName,
                        functionIdentifier = function.id,
                        functionParameters = parameters,
                    )
                }

            val response =
                if (hasAppInteractionManager()) {
                    val platformRequest = request.toPlatformExecuteAppFunctionRequest()
                    val session = getSession(threadId, request.targetPackageName)
                    val platformResponse =
                        suspendCancellableCoroutine { continuation ->
                            context.getSystemService(android.app.appfunctions.AppFunctionManager::class.java)
                                .executeAppFunction(
                                    session,
                                    platformRequest,
                                    Runnable::run,
                                    CancellationSignal(),
                                    continuation.asOutcomeReceiver(),
                                )
                        }
                    platformResponse.toCompatExecuteAppFunctionResponse(function).also {
                        if (threadId == null) {
                            session.close()
                        }
                    }
                } else {
                    appFunctionManager.executeAppFunction(request)
                }

            return try {
                when (response) {
                    is ExecuteAppFunctionResponse.Success -> {
                        val data = response.returnValue
                        val valueType = function.response.valueType
                        if (valueType is AppFunctionParcelableTypeMetadata &&
                            valueType.qualifiedName == PendingIntent::class.java.name
                        ) {
                            val pendingIntent =
                                data.getParcelable(PROPERTY_RETURN_VALUE, PendingIntent::class.java)
                            if (pendingIntent != null) {
                                ExecuteAppFunctionResult.PendingIntentAction(pendingIntent)
                            } else {
                                ExecuteAppFunctionResult.Error(
                                    Exception("Failed to extract PendingIntent from response"),
                                )
                            }
                        } else {
                            val jsonString =
                                convertAppFunctionDataToJsonUseCase(
                                    PROPERTY_RETURN_VALUE,
                                    data,
                                    valueType,
                                    function.components,
                                )
                            ExecuteAppFunctionResult.Data(data, jsonString)
                        }
                    }
                    is ExecuteAppFunctionResponse.Error -> {
                        ExecuteAppFunctionResult.Error(response.error)
                    }
                }
            } catch (e: Exception) {
                ExecuteAppFunctionResult.Error(e)
            }
        }

        private suspend fun getSession(
            threadId: String?,
            targetPackage: String,
        ): AppInteractionSession {
            check(hasAppInteractionManager())
            if (threadId != null && availableSession.contains(threadId)) {
                return checkNotNull(availableSession[threadId])
            }
            val appInteractionManager = context.getSystemService(AppInteractionManager::class.java)
            val createParams =
                AppInteractionSession.CreateParams.Builder()
                    .setOnStartIntentSenderCallback(
                        context.mainExecutor,
                        { intentSender ->
                            context.startIntentSender(
                                intentSender,
                                Intent().apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                                0,
                                0,
                                0,
                                null,
                            )
                        },
                    )
                    .setTargetPackages(listOf(targetPackage))
                    .build()
            return suspendCancellableCoroutine { continuation ->
                appInteractionManager.createSession(createParams, Runnable::run, continuation.asOutcomeReceiver())
            }.also { session ->
                if (threadId != null) {
                    availableSession[threadId] = session
                }
            }
        }

        private fun hasAppInteractionManager(): Boolean {
            return context.getSystemService("app_interaction") != null
        }
    }

/** Sealed class representing the result of an AppFunction execution. */
sealed class ExecuteAppFunctionResult {
    data class Data(val data: AppFunctionData, val formattedJson: String) :
        ExecuteAppFunctionResult()

    data class PendingIntentAction(val pendingIntent: PendingIntent) : ExecuteAppFunctionResult()

    data class Error(val exception: Exception) : ExecuteAppFunctionResult()
}

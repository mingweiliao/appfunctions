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
package com.example.appfunctions.agent.ui.screens.agentdemo

import androidx.lifecycle.ViewModel
import com.example.appfunctions.agent.domain.appfunction.ConnectedAppInfo
import com.example.appfunctions.agent.domain.appfunction.GetConnectedAppsUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedAppsViewModelTest {
    private lateinit var viewModel: ConnectedAppsViewModel
    private val mockGetConnectedAppsUseCase: GetConnectedAppsUseCase = mockk()
    private lateinit var fakeRepository: FakeSettingsRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSettingsRepository()
        every { mockGetConnectedAppsUseCase() } returns flowOf(emptyList())
        viewModel = ConnectedAppsViewModel(mockGetConnectedAppsUseCase, fakeRepository)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.clearForTest()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_reflectsUseCaseValues() =
        runTest {
            val appInfoList =
                listOf(
                    ConnectedAppInfo("com.example.app1", "App 1", null, true),
                    ConnectedAppInfo("com.example.app2", "App 2", null, false),
                )
            every { mockGetConnectedAppsUseCase() } returns flowOf(appInfoList)

            // Recreate ViewModel to collect new flow
            viewModel = ConnectedAppsViewModel(mockGetConnectedAppsUseCase, fakeRepository)

            val job = backgroundScope.launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val uiState = viewModel.uiState.value
            assertEquals(appInfoList, uiState.connectedApps)

            job.cancel()
        }

    @Test
    fun setAppConnected_updatesRepository() =
        runTest {
            viewModel.setAppConnected("test.package", false)

            val disconnectedApps = fakeRepository.disconnectedApps.first()
            assertEquals(setOf("test.package"), disconnectedApps)
        }

    private fun ViewModel.clearForTest() {
        val method = ViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(this)
    }
}

/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.scheduling.activities;

import static io.airbyte.config.EnvConfigs.DEFAULT_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_CONNECTION_DISABLE;
import static io.airbyte.config.EnvConfigs.DEFAULT_FAILED_JOBS_IN_A_ROW_BEFORE_CONNECTION_DISABLE;
import static io.airbyte.scheduler.models.Job.REPLICATION_TYPES;
import static io.airbyte.scheduler.persistence.JobNotifier.CONNECTION_DISABLED_NOTIFICATION;
import static io.airbyte.scheduler.persistence.JobNotifier.CONNECTION_DISABLED_WARNING_NOTIFICATION;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.eq;

import io.airbyte.commons.features.FeatureFlags;
import io.airbyte.config.Configs;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSync.Status;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.scheduler.models.Job;
import io.airbyte.scheduler.models.JobStatus;
import io.airbyte.scheduler.persistence.JobNotifier;
import io.airbyte.scheduler.persistence.JobPersistence;
import io.airbyte.validation.json.JsonValidationException;
import io.airbyte.workers.temporal.scheduling.activities.AutoDisableConnectionActivity.AutoDisableConnectionActivityInput;
import io.airbyte.workers.temporal.scheduling.activities.AutoDisableConnectionActivity.AutoDisableConnectionOutput;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AutoDisableConnectionActivityTest {

  @Mock
  private FeatureFlags mFeatureFlags;

  @Mock
  private ConfigRepository mConfigRepository;

  @Mock
  private JobPersistence mJobPersistence;

  @Mock
  private Configs mConfigs;

  @Mock
  private JobNotifier mJobNotifier;

  @Mock
  private Job mJob;

  @InjectMocks
  private AutoDisableConnectionActivityImpl autoDisableActivity;

  private static final UUID CONNECTION_ID = UUID.randomUUID();
  private static final Instant CURR_INSTANT = Instant.now();
  private static final AutoDisableConnectionActivityInput ACTIVITY_INPUT = new AutoDisableConnectionActivityInput(CONNECTION_ID, CURR_INSTANT);
  private static final int MAX_FAILURE_JOBS_IN_A_ROW = DEFAULT_FAILED_JOBS_IN_A_ROW_BEFORE_CONNECTION_DISABLE;
  private static final int MAX_DAYS_OF_ONLY_FAILED_JOBS = DEFAULT_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_CONNECTION_DISABLE;
  private static final int MAX_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_WARNING = DEFAULT_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_CONNECTION_DISABLE / 2;

  private final StandardSync standardSync = new StandardSync();

  @BeforeEach
  void setUp() throws IOException {
    standardSync.setStatus(Status.ACTIVE);
    Mockito.when(mFeatureFlags.autoDisablesFailingConnections()).thenReturn(true);
    Mockito.when(mConfigs.getMaxDaysOfOnlyFailedJobsBeforeConnectionDisable()).thenReturn(MAX_DAYS_OF_ONLY_FAILED_JOBS);
    Mockito.when(mJobPersistence.getLastReplicationJob(CONNECTION_ID)).thenReturn(Optional.of(mJob));
  }

  // test warnings

  @Test
  @DisplayName("Test that a notification warning is sent for connections that have failed `MAX_FAILURE_JOBS_IN_A_ROW / 2` times")
  public void testWarningNotificationsForAutoDisablingMaxNumFailures() throws IOException {
    // from most recent to least recent: MAX_FAILURE_JOBS_IN_A_ROW/2 and 1 success
    final List<JobStatus> jobStatuses = new ArrayList<>(Collections.nCopies(MAX_FAILURE_JOBS_IN_A_ROW / 2, JobStatus.FAILED));
    jobStatuses.add(JobStatus.SUCCEEDED);

    Mockito.when(mJobPersistence.getFirstReplicationJob(CONNECTION_ID)).thenReturn(Optional.of(mJob));
    Mockito.when(mConfigs.getMaxFailedJobsInARowBeforeConnectionDisable()).thenReturn(MAX_FAILURE_JOBS_IN_A_ROW);
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS))).thenReturn(jobStatuses);

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isFalse();
    assertThat(standardSync.getStatus()).isEqualTo(Status.ACTIVE);
    Mockito.verify(mJobNotifier, Mockito.only()).autoDisableConnectionAlertWithoutCustomerioConfig(eq(CONNECTION_DISABLED_WARNING_NOTIFICATION),
        Mockito.any());
  }

  @Test
  @DisplayName("Test that a notification warning is sent after only failed jobs in last `MAX_DAYS_OF_STRAIGHT_FAILURE / 2` days")
  public void testWarningNotificationsForAutoDisablingMaxDaysOfFailure() throws IOException {
    Mockito.when(mConfigs.getMaxDaysOfOnlyFailedJobsBeforeConnectionDisable()).thenReturn(MAX_DAYS_OF_ONLY_FAILED_JOBS);
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS)))
        .thenReturn(Collections.singletonList(JobStatus.FAILED));
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_WARNING, ChronoUnit.DAYS)))
        .thenReturn(Collections.singletonList(JobStatus.FAILED));

    Mockito.when(mConfigs.getMaxFailedJobsInARowBeforeConnectionDisable()).thenReturn(MAX_FAILURE_JOBS_IN_A_ROW);
    Mockito.when(mJobPersistence.getFirstReplicationJob(CONNECTION_ID)).thenReturn(Optional.of(mJob));
    Mockito.when(mJob.getCreatedAtInSecond()).thenReturn(
        CURR_INSTANT.getEpochSecond() - TimeUnit.DAYS.toSeconds(MAX_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_WARNING));

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isFalse();
    assertThat(standardSync.getStatus()).isEqualTo(Status.ACTIVE);
    Mockito.verify(mJobNotifier, Mockito.only()).autoDisableConnectionAlertWithoutCustomerioConfig(eq(CONNECTION_DISABLED_WARNING_NOTIFICATION),
        Mockito.any());
  }

  @Test
  @DisplayName("Test that the connection is _not_ disabled and no warning is sent after only failed jobs and oldest job is less than `MAX_DAYS_OF_STRAIGHT_FAILURE / 2 `days old")
  public void testOnlyFailuresButFirstJobYoungerThanMaxDaysWarning() throws IOException {
    Mockito.when(mConfigs.getMaxDaysOfOnlyFailedJobsBeforeConnectionDisable()).thenReturn(MAX_DAYS_OF_ONLY_FAILED_JOBS);
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS)))
        .thenReturn(Collections.singletonList(JobStatus.FAILED));
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_WARNING, ChronoUnit.DAYS)))
        .thenReturn(Collections.singletonList(JobStatus.FAILED));

    Mockito.when(mConfigs.getMaxFailedJobsInARowBeforeConnectionDisable()).thenReturn(MAX_FAILURE_JOBS_IN_A_ROW);
    Mockito.when(mJobPersistence.getFirstReplicationJob(CONNECTION_ID)).thenReturn(Optional.of(mJob));
    Mockito.when(mJob.getCreatedAtInSecond()).thenReturn(CURR_INSTANT.getEpochSecond());

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isFalse();
    assertThat(standardSync.getStatus()).isEqualTo(Status.ACTIVE);
    Mockito.verify(mJobNotifier, Mockito.never()).autoDisableConnectionAlertWithoutCustomerioConfig(Mockito.anyString(), Mockito.any());
  }

  // test should disable / shouldn't disable cases

  @Test
  @DisplayName("Test that the connection is disabled after MAX_FAILURE_JOBS_IN_A_ROW straight failures")
  public void testMaxFailuresInARow() throws IOException, JsonValidationException, ConfigNotFoundException {
    // from most recent to least recent: MAX_FAILURE_JOBS_IN_A_ROW and 1 success
    final List<JobStatus> jobStatuses = new ArrayList<>(Collections.nCopies(MAX_FAILURE_JOBS_IN_A_ROW, JobStatus.FAILED));
    jobStatuses.add(JobStatus.SUCCEEDED);

    Mockito.when(mConfigs.getMaxFailedJobsInARowBeforeConnectionDisable()).thenReturn(MAX_FAILURE_JOBS_IN_A_ROW);
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS))).thenReturn(jobStatuses);
    Mockito.when(mConfigRepository.getStandardSync(CONNECTION_ID)).thenReturn(standardSync);

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isTrue();
    assertThat(standardSync.getStatus()).isEqualTo(Status.INACTIVE);
    Mockito.verify(mJobNotifier, Mockito.only()).autoDisableConnectionAlertWithoutCustomerioConfig(eq(CONNECTION_DISABLED_NOTIFICATION),
        Mockito.any());
  }

  @Test
  @DisplayName("Test that the connection is _not_ disabled after MAX_FAILURE_JOBS_IN_A_ROW - 1 straight failures")
  public void testLessThanMaxFailuresInARow() throws IOException {
    // from most recent to least recent: MAX_FAILURE_JOBS_IN_A_ROW-1 and 1 success
    final List<JobStatus> jobStatuses = new ArrayList<>(Collections.nCopies(MAX_FAILURE_JOBS_IN_A_ROW - 1, JobStatus.FAILED));
    jobStatuses.add(JobStatus.SUCCEEDED);

    Mockito.when(mJobPersistence.getFirstReplicationJob(CONNECTION_ID)).thenReturn(Optional.of(mJob));
    Mockito.when(mConfigs.getMaxFailedJobsInARowBeforeConnectionDisable()).thenReturn(MAX_FAILURE_JOBS_IN_A_ROW);
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS))).thenReturn(jobStatuses);
    Mockito.when(mJob.getCreatedAtInSecond()).thenReturn(
        CURR_INSTANT.getEpochSecond() - TimeUnit.DAYS.toSeconds(MAX_DAYS_OF_ONLY_FAILED_JOBS));

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isFalse();
    assertThat(standardSync.getStatus()).isEqualTo(Status.ACTIVE);

    // check that no notification has been sent
    Mockito.verify(mJobNotifier, Mockito.never()).autoDisableConnectionAlertWithoutCustomerioConfig(Mockito.anyString(), Mockito.any());
  }

  @Test
  @DisplayName("Test that the connection is _not_ disabled after 0 jobs in last MAX_DAYS_OF_STRAIGHT_FAILURE days")
  public void testNoRuns() throws IOException {
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS))).thenReturn(Collections.emptyList());

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isFalse();
    assertThat(standardSync.getStatus()).isEqualTo(Status.ACTIVE);
    Mockito.verify(mJobNotifier, Mockito.never()).autoDisableConnectionAlertWithoutCustomerioConfig(Mockito.anyString(), Mockito.any());
  }

  @Test
  @DisplayName("Test that the connection is disabled after only failed jobs in last MAX_DAYS_OF_STRAIGHT_FAILURE days")
  public void testOnlyFailuresInMaxDays() throws IOException, JsonValidationException, ConfigNotFoundException {
    Mockito.when(mConfigs.getMaxDaysOfOnlyFailedJobsBeforeConnectionDisable()).thenReturn(MAX_DAYS_OF_ONLY_FAILED_JOBS);
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS)))
        .thenReturn(Collections.singletonList(JobStatus.FAILED));

    Mockito.when(mConfigs.getMaxFailedJobsInARowBeforeConnectionDisable()).thenReturn(MAX_FAILURE_JOBS_IN_A_ROW);
    Mockito.when(mJobPersistence.getFirstReplicationJob(CONNECTION_ID)).thenReturn(Optional.of(mJob));
    Mockito.when(mJob.getCreatedAtInSecond()).thenReturn(
        CURR_INSTANT.getEpochSecond() - TimeUnit.DAYS.toSeconds(MAX_DAYS_OF_ONLY_FAILED_JOBS));
    Mockito.when(mConfigRepository.getStandardSync(CONNECTION_ID)).thenReturn(standardSync);

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isTrue();
    assertThat(standardSync.getStatus()).isEqualTo(Status.INACTIVE);
    Mockito.verify(mJobNotifier, Mockito.only()).autoDisableConnectionAlertWithoutCustomerioConfig(eq(CONNECTION_DISABLED_NOTIFICATION),
        Mockito.any());
  }

  @Test
  @DisplayName("Test that the connection is _not_ disabled after only cancelled jobs")
  public void testIgnoreOnlyCancelledRuns() throws IOException {
    Mockito.when(mJobPersistence.listJobStatusWithConnection(CONNECTION_ID, REPLICATION_TYPES,
        CURR_INSTANT.minus(MAX_DAYS_OF_ONLY_FAILED_JOBS, ChronoUnit.DAYS)))
        .thenReturn(Collections.singletonList(JobStatus.CANCELLED));

    final AutoDisableConnectionOutput output = autoDisableActivity.autoDisableFailingConnection(ACTIVITY_INPUT);
    assertThat(output.isDisabled()).isFalse();
    assertThat(standardSync.getStatus()).isEqualTo(Status.ACTIVE);
    Mockito.verify(mJobNotifier, Mockito.never()).autoDisableConnectionAlertWithoutCustomerioConfig(Mockito.anyString(), Mockito.any());
  }

}

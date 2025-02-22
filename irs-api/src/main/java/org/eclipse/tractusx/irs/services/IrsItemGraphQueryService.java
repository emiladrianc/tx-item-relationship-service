/********************************************************************************
 * Copyright (c) 2021,2022
 *       2022: Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *       2022: ZF Friedrichshafen AG
 *       2022: ISTOS GmbH
 * Copyright (c) 2021,2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0. *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/
package org.eclipse.tractusx.irs.services;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.tractusx.irs.aaswrapper.job.AASTransferProcess;
import org.eclipse.tractusx.irs.aaswrapper.job.ItemContainer;
import org.eclipse.tractusx.irs.aaswrapper.job.ItemDataRequest;
import org.eclipse.tractusx.irs.aaswrapper.job.RequestMetric;
import org.eclipse.tractusx.irs.component.AsyncFetchedItems;
import org.eclipse.tractusx.irs.component.Bpn;
import org.eclipse.tractusx.irs.component.FetchedItems;
import org.eclipse.tractusx.irs.component.Job;
import org.eclipse.tractusx.irs.component.JobHandle;
import org.eclipse.tractusx.irs.component.JobParameter;
import org.eclipse.tractusx.irs.component.JobStatusResult;
import org.eclipse.tractusx.irs.component.Jobs;
import org.eclipse.tractusx.irs.component.PageResult;
import org.eclipse.tractusx.irs.component.RegisterJob;
import org.eclipse.tractusx.irs.component.Relationship;
import org.eclipse.tractusx.irs.component.Submodel;
import org.eclipse.tractusx.irs.component.Summary;
import org.eclipse.tractusx.irs.component.Tombstone;
import org.eclipse.tractusx.irs.component.assetadministrationshell.AssetAdministrationShellDescriptor;
import org.eclipse.tractusx.irs.component.enums.AspectType;
import org.eclipse.tractusx.irs.component.enums.BomLifecycle;
import org.eclipse.tractusx.irs.component.enums.Direction;
import org.eclipse.tractusx.irs.component.enums.JobState;
import org.eclipse.tractusx.irs.connector.job.JobInitiateResponse;
import org.eclipse.tractusx.irs.connector.job.JobOrchestrator;
import org.eclipse.tractusx.irs.connector.job.JobStore;
import org.eclipse.tractusx.irs.connector.job.MultiTransferJob;
import org.eclipse.tractusx.irs.connector.job.ResponseStatus;
import org.eclipse.tractusx.irs.connector.job.TransferProcess;
import org.eclipse.tractusx.irs.exceptions.EntityNotFoundException;
import org.eclipse.tractusx.irs.persistence.BlobPersistence;
import org.eclipse.tractusx.irs.persistence.BlobPersistenceException;
import org.eclipse.tractusx.irs.util.JsonUtil;
import org.springframework.beans.support.MutableSortDefinition;
import org.springframework.beans.support.PagedListHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Service for retrieving parts tree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({ "PMD.ExcessiveImports",
                    "PMD.TooManyMethods"
})
public class IrsItemGraphQueryService implements IIrsItemGraphQueryService {

    private final JobOrchestrator<ItemDataRequest, AASTransferProcess> orchestrator;

    private final JobStore jobStore;

    private final BlobPersistence blobStore;

    private final MeterRegistryService meterRegistryService;

    @Override
    public PageResult getJobsByState(final @NonNull List<JobState> states, final Pageable pageable) {
        final List<MultiTransferJob> jobs = states.isEmpty() ? jobStore.findAll() : jobStore.findByStates(states);
        final List<JobStatusResult> jobStatusResults = jobs.stream()
                                                           .map(job -> JobStatusResult.builder()
                                                                                      .id(job.getJob().getId())
                                                                                      .state(job.getJob().getState())
                                                                                      .startedOn(job.getJob()
                                                                                                    .getStartedOn())
                                                                                      .completedOn(job.getJob()
                                                                                                      .getCompletedOn())
                                                                                      .build())
                                                           .toList();

        return new PageResult(paginateAndSortResults(pageable, jobStatusResults));
    }

    @Override
    public PageResult getJobsByState(@NonNull final List<JobState> states, @NonNull final List<JobState> jobStates,
            final Pageable pageable) {
        return getJobsByState(states.isEmpty() ? jobStates : states, pageable);
    }

    private PagedListHolder<JobStatusResult> paginateAndSortResults(final Pageable pageable,
            final List<JobStatusResult> results) {
        final PagedListHolder<JobStatusResult> pageListHolder = new PagedListHolder<>(new ArrayList<>(results));

        final Sort sort = pageable.getSortOr(Sort.by(Sort.Direction.DESC, "startedOn"));
        if (sort.isSorted()) {
            sort.stream().findFirst().ifPresent(order -> {
                pageListHolder.setSort(new MutableSortDefinition(order.getProperty(), true, order.isAscending()));
                pageListHolder.resort();
            });
        }
        pageListHolder.setPage(pageable.getPageNumber());
        pageListHolder.setPageSize(pageable.getPageSize());

        return pageListHolder;
    }

    @Override
    public JobHandle registerItemJob(final @NonNull RegisterJob request) {
        final var params = buildJobParameter(request);
        if (params.getBomLifecycle().equals(BomLifecycle.AS_PLANNED) && params.getDirection()
                                                                              .equals(Direction.UPWARD)) {
            // Currently not supported variant
            throw new IllegalArgumentException("BomLifecycle asPlanned with direction upward is not supported yet!");
        }

        final JobInitiateResponse jobInitiateResponse = orchestrator.startJob(request.getGlobalAssetId(), params);
        meterRegistryService.incrementNumberOfCreatedJobs();

        if (jobInitiateResponse.getStatus().equals(ResponseStatus.OK)) {
            final String jobId = jobInitiateResponse.getJobId();
            return JobHandle.builder().id(UUID.fromString(jobId)).build();
        } else {
            throw new IllegalArgumentException("Could not start job: " + jobInitiateResponse.getError());
        }
    }

    private JobParameter buildJobParameter(final @NonNull RegisterJob request) {
        final BomLifecycle bomLifecycle = Optional.ofNullable(request.getBomLifecycle()).orElse(BomLifecycle.AS_BUILT);
        final List<AspectType> aspectTypeValues = Optional.ofNullable(request.getAspects())
                                                          .orElse(List.of(bomLifecycle.getDefaultAspect()));
        final Direction direction = Optional.ofNullable(request.getDirection()).orElse(Direction.DOWNWARD);

        return JobParameter.builder()
                           .depth(request.getDepth())
                           .bomLifecycle(bomLifecycle)
                           .direction(direction)
                           .aspects(aspectTypeValues.isEmpty()
                                   ? List.of(bomLifecycle.getDefaultAspect())
                                   : aspectTypeValues)
                           .collectAspects(request.isCollectAspects())
                           .lookupBPNs(request.isLookupBPNs())
                           .callbackUrl(request.getCallbackUrl())
                           .build();
    }

    @Override
    public Job cancelJobById(final @NonNull UUID jobId) {
        final String idAsString = String.valueOf(jobId);

        final Optional<MultiTransferJob> canceled = this.jobStore.cancelJob(idAsString);
        return canceled.orElseThrow(() -> new EntityNotFoundException("No job exists with id " + jobId)).getJob();
    }

    @Override
    public Jobs getJobForJobId(final UUID jobId, final boolean includePartialResults) {
        log.info("Retrieving job with id {} (includePartialResults: {})", jobId, includePartialResults);

        final Optional<MultiTransferJob> multiTransferJob = jobStore.find(jobId.toString());

        if (multiTransferJob.isPresent()) {
            final MultiTransferJob multiJob = multiTransferJob.get();

            final var relationships = new ArrayList<Relationship>();
            final var tombstones = new ArrayList<Tombstone>();
            final var shells = new ArrayList<AssetAdministrationShellDescriptor>();
            final var submodels = new ArrayList<Submodel>();
            final var bpns = new ArrayList<Bpn>();

            if (jobIsCompleted(multiJob)) {
                final var container = retrieveJobResultRelationships(multiJob.getJob().getId());
                relationships.addAll(container.getRelationships());
                tombstones.addAll(container.getTombstones());
                shells.addAll(container.getShells());
                submodels.addAll(container.getSubmodels());
                bpns.addAll(container.getBpns());
            } else if (includePartialResults) {
                final var container = retrievePartialResults(multiJob);
                relationships.addAll(container.getRelationships());
                tombstones.addAll(container.getTombstones());
                shells.addAll(container.getShells());
                submodels.addAll(container.getSubmodels());
                bpns.addAll(container.getBpns());
            }

            log.info("Found job with id {} in status {} with {} relationships, {} tombstones and {} submodels", jobId,
                    multiJob.getJob().getState(), relationships.size(), tombstones.size(), submodels.size());

            return Jobs.builder()
                       .job(multiJob.getJob()
                                    .toBuilder()
                                    .summary(buildSummary(multiJob.getCompletedTransfers().size(),
                                            multiJob.getTransferProcessIds().size(), tombstones.size(),
                                            retrievePartialResults(multiJob)))
                                    .build())
                       .relationships(relationships)
                       .tombstones(tombstones)
                       .shells(shells)
                       .submodels(submodels)
                       .bpns(bpns)
                       .build();
        } else {
            throw new EntityNotFoundException("No job exists with id " + jobId);
        }
    }

    @Scheduled(cron = "${irs.job.jobstore.cron.expression}")
    public void updateJobsInJobStoreMetrics() {
        final List<MultiTransferJob> jobs = jobStore.findAll();
        final long numberOfJobs = jobs.size();
        log.debug("Number(s) of job in JobStore: {}", numberOfJobs);
        meterRegistryService.setNumberOfJobsInJobStore(numberOfJobs);

        final Map<JobState, Long> stateCount = jobs.stream()
                                                   .map(MultiTransferJob::getJob)
                                                   .map(Job::getState)
                                                   .collect(Collectors.groupingBy(Function.identity(),
                                                           Collectors.counting()));

        for (final JobState state : JobState.values()) {
            meterRegistryService.setStateSnapShot(state, stateCount.getOrDefault(state, 0L));
        }

    }

    private Summary buildSummary(final int completedTransfersSize, final int runningSize, final int tombstonesSize,
            final ItemContainer itemContainer) {
        final Integer bpnLookupCompleted = getBpnLookupMetric(itemContainer, RequestMetric::getCompleted);
        final Integer bpnLookupFailed = getBpnLookupMetric(itemContainer, RequestMetric::getFailed);
        return Summary.builder()
                      .asyncFetchedItems(AsyncFetchedItems.builder()
                                                          .completed(completedTransfersSize)
                                                          .running(runningSize)
                                                          .failed(tombstonesSize - bpnLookupFailed)
                                                          .build())
                      .bpnLookups(FetchedItems.builder().completed(bpnLookupCompleted).failed(bpnLookupFailed).build())
                      .build();
    }

    private Integer getBpnLookupMetric(final ItemContainer itemContainer,
            final Function<RequestMetric, Integer> requestMetricIntegerFunction) {
        return itemContainer.getMetrics()
                            .stream()
                            .filter(metric -> metric.getType().equals(RequestMetric.RequestType.BPDM))
                            .map(requestMetricIntegerFunction)
                            .reduce(Integer::sum)
                            .orElse(0);
    }

    private ItemContainer retrievePartialResults(final MultiTransferJob multiJob) {
        final List<TransferProcess> completedTransfers = multiJob.getCompletedTransfers();
        final List<String> transferIds = completedTransfers.stream().map(TransferProcess::getId).toList();

        final var relationships = new ArrayList<Relationship>();
        final var tombstones = new ArrayList<Tombstone>();
        final var shells = new ArrayList<AssetAdministrationShellDescriptor>();
        final var submodels = new ArrayList<Submodel>();
        final var metrics = new ArrayList<RequestMetric>();
        final var bpns = new ArrayList<Bpn>();

        for (final String id : transferIds) {
            try {
                final Optional<byte[]> blob = blobStore.getBlob(id);
                blob.ifPresent(bytes -> {
                    final ItemContainer itemContainer = toItemContainer(bytes);
                    relationships.addAll(itemContainer.getRelationships());
                    tombstones.addAll(itemContainer.getTombstones());
                    shells.addAll(itemContainer.getShells());
                    submodels.addAll(itemContainer.getSubmodels());
                    metrics.addAll(itemContainer.getMetrics());
                    bpns.addAll(itemContainer.getBpns());
                });

            } catch (BlobPersistenceException e) {
                meterRegistryService.incrementException();
                log.error("Unable to read transfer result", e);
            }
        }
        return ItemContainer.builder()
                            .relationships(relationships)
                            .tombstones(tombstones)
                            .shells(shells)
                            .submodels(submodels)
                            .metrics(metrics)
                            .bpns(bpns)
                            .build();
    }

    private ItemContainer toItemContainer(final byte[] blob) {
        return new JsonUtil().fromString(new String(blob, StandardCharsets.UTF_8), ItemContainer.class);
    }

    private ItemContainer retrieveJobResultRelationships(final UUID jobId) {
        try {
            final Optional<byte[]> blob = blobStore.getBlob(jobId.toString());
            final byte[] bytes = blob.orElseThrow(
                    () -> new EntityNotFoundException("Could not find stored data for multiJob with id " + jobId));
            return toItemContainer(bytes);
        } catch (BlobPersistenceException e) {
            log.error("Unable to read blob", e);
            meterRegistryService.incrementException();
            throw new EntityNotFoundException("Could not load stored data for multiJob with id " + jobId, e);
        }
    }

    private boolean jobIsCompleted(final MultiTransferJob multiJob) {
        return multiJob.getJob().getState().equals(JobState.COMPLETED);
    }

}

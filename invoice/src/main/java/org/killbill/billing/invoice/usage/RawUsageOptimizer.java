/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.invoice.usage;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.joda.time.LocalDate;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Usage;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.dao.InvoiceDao;
import org.killbill.billing.invoice.dao.InvoiceTrackingModelDao;
import org.killbill.billing.invoice.generator.InvoiceDateUtils;
import org.killbill.billing.invoice.generator.InvoiceWithMetadata.TrackingRecordId;
import org.killbill.billing.invoice.model.UsageInvoiceItem;
import org.killbill.billing.usage.InternalUserApi;
import org.killbill.billing.usage.api.RawUsageRecord;
import org.killbill.billing.util.config.definition.InvoiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Ordering;

public class RawUsageOptimizer {

    private static final Ordering<InvoiceItem> USAGE_ITEM_ORDERING = Ordering.natural()
                                                                             .onResultOf(new Function<InvoiceItem, Comparable>() {
                                                                                 @Override
                                                                                 public Comparable apply(final InvoiceItem invoiceItem) {
                                                                                     return invoiceItem.getEndDate();
                                                                                 }
                                                                             });

    private static final Logger log = LoggerFactory.getLogger(RawUsageOptimizer.class);

    private final InternalUserApi usageApi;
    private final InvoiceConfig config;
    private final InvoiceDao invoiceDao;

    @Inject
    public RawUsageOptimizer(final InvoiceConfig config, final InvoiceDao invoiceDao, final InternalUserApi usageApi) {
        this.usageApi = usageApi;
        this.config = config;
        this.invoiceDao = invoiceDao;
    }

    public RawUsageOptimizerResult getInArrearUsage(final LocalDate firstEventStartDate, final LocalDate targetDate, final Iterable<InvoiceItem> existingUsageItems, final Map<String, Usage> knownUsage, final InternalCallContext internalCallContext) {
        final int configRawUsagePreviousPeriod = config.getMaxRawUsagePreviousPeriod(internalCallContext);
        final LocalDate optimizedStartDate = configRawUsagePreviousPeriod >= 0 ? getOptimizedRawUsageStartDate(firstEventStartDate, targetDate, existingUsageItems, knownUsage, internalCallContext) : firstEventStartDate;
        log.debug("RawUsageOptimizerResult accountRecordId='{}', configRawUsagePreviousPeriod='{}', firstEventStartDate='{}', optimizedStartDate='{}',  targetDate='{}'",
                  internalCallContext.getAccountRecordId(), configRawUsagePreviousPeriod, firstEventStartDate, optimizedStartDate, targetDate);
        final List<RawUsageRecord> rawUsageData = usageApi.getRawUsageForAccount(optimizedStartDate, targetDate, internalCallContext);

        final List<InvoiceTrackingModelDao> trackingIds = invoiceDao.getTrackingsByDateRange(optimizedStartDate, targetDate, internalCallContext);
        final Set<TrackingRecordId> existingTrackingIds = new HashSet<TrackingRecordId>();
        for (final InvoiceTrackingModelDao invoiceTrackingModelDao : trackingIds) {
            existingTrackingIds.add(new TrackingRecordId(invoiceTrackingModelDao.getTrackingId(), invoiceTrackingModelDao.getInvoiceId(), invoiceTrackingModelDao.getSubscriptionId(), invoiceTrackingModelDao.getUnitType(), invoiceTrackingModelDao.getRecordDate()));
        }
        return new RawUsageOptimizerResult(optimizedStartDate, rawUsageData, existingTrackingIds);
    }

    @VisibleForTesting
    LocalDate getOptimizedRawUsageStartDate(final LocalDate firstEventStartDate, final LocalDate targetDate, final Iterable<InvoiceItem> existingUsageItems, final Map<String, Usage> knownUsage, final InternalCallContext internalCallContext) {
        if (!existingUsageItems.iterator().hasNext()) {
            return firstEventStartDate;

        }
        // Extract all usage billing period known in that catalog
        final Collection<BillingPeriod> knownUsageBillingPeriod = new HashSet<BillingPeriod>();
        for (final Usage usage : knownUsage.values()) {
            knownUsageBillingPeriod.add(usage.getBillingPeriod());
        }

        // Make sure all usage items are sorted by endDate
        final List<InvoiceItem> sortedUsageItems = USAGE_ITEM_ORDERING.sortedCopy(existingUsageItems);

        // Compute an array with one date per BillingPeriod:
        // If BillingPeriod is never defined in the catalog (no need to look for items), we initialize its value
        // such that it cannot be chosen
        //
        final LocalDate[] perBillingPeriodMostRecentConsumableInArrearItemEndDate = new LocalDate[BillingPeriod.values().length - 1]; // Exclude the NO_BILLING_PERIOD
        int idx = 0;
        for (final BillingPeriod bp : BillingPeriod.values()) {
            if (bp != BillingPeriod.NO_BILLING_PERIOD) {
                final LocalDate makerDateThanCannotBeChosenAsTheMinOfAllDates = InvoiceDateUtils.advanceByNPeriods(targetDate, bp, config.getMaxRawUsagePreviousPeriod(internalCallContext));
                perBillingPeriodMostRecentConsumableInArrearItemEndDate[idx++] = (knownUsageBillingPeriod.contains(bp)) ? null : makerDateThanCannotBeChosenAsTheMinOfAllDates;
            }
        }

        final ListIterator<InvoiceItem> iterator = sortedUsageItems.listIterator(sortedUsageItems.size());
        while (iterator.hasPrevious()) {
            final InvoiceItem item = iterator.previous();
            if (!(item instanceof UsageInvoiceItem)) {
                // Help to debug https://github.com/killbill/killbill/issues/1095
                log.warn("RawUsageOptimizer : item id={}, expected to see an UsageInvoiceItem type, got class {}, classloader = {}",
                         item.getId(),
                         item.getClass(),
                         item.getClass().getClassLoader());
            }
            final Usage usage = knownUsage.get(item.getUsageName());

            if (perBillingPeriodMostRecentConsumableInArrearItemEndDate[usage.getBillingPeriod().ordinal()] == null) {
                perBillingPeriodMostRecentConsumableInArrearItemEndDate[usage.getBillingPeriod().ordinal()] = item.getEndDate();
                if (!containsNullEntries(perBillingPeriodMostRecentConsumableInArrearItemEndDate)) {
                    break;
                }
            }
        }

        // Extract the min from all the dates
        LocalDate targetStartDate = null;
        idx = 0;
        for (final BillingPeriod bp : BillingPeriod.values()) {
            if (bp != BillingPeriod.NO_BILLING_PERIOD) {
                final LocalDate tmp = perBillingPeriodMostRecentConsumableInArrearItemEndDate[idx];
                final LocalDate targetBillingPeriodDate = tmp != null ? InvoiceDateUtils.recedeByNPeriods(tmp, bp, config.getMaxRawUsagePreviousPeriod(internalCallContext)) : null;
                if (targetStartDate == null || (targetBillingPeriodDate != null && targetBillingPeriodDate.compareTo(targetStartDate) < 0)) {
                    targetStartDate = targetBillingPeriodDate;
                }
                idx++;
            }
        }

        final LocalDate result = targetStartDate != null && targetStartDate.compareTo(firstEventStartDate) > 0 ? targetStartDate : firstEventStartDate;
        return result;
    }

    private boolean containsNullEntries(final LocalDate[] entries) {
        boolean result = false;
        for (final LocalDate entry : entries) {
            if (entry == null) {
                result = true;
                break;
            }
        }
        return result;
    }

    public static class RawUsageOptimizerResult {

        private final LocalDate rawUsageStartDate;
        private final List<RawUsageRecord> rawUsage;
        private final Set<TrackingRecordId> existingTrackingIds;

        public RawUsageOptimizerResult(final LocalDate rawUsageStartDate, final List<RawUsageRecord> rawUsage, final Set<TrackingRecordId> existingTrackingIds) {
            this.rawUsageStartDate = rawUsageStartDate;
            this.rawUsage = rawUsage;
            this.existingTrackingIds = existingTrackingIds;
        }

        public LocalDate getRawUsageStartDate() {
            return rawUsageStartDate;
        }

        public List<RawUsageRecord> getRawUsage() {
            return rawUsage;
        }

        public Set<TrackingRecordId> getExistingTrackingIds() {
            return existingTrackingIds;
        }
    }
}

/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azuretools.authmanage;

import com.microsoft.azure.management.resources.Subscription;
import com.microsoft.azure.management.resources.Tenant;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azuretools.authmanage.models.SubscriptionDetail;
import com.microsoft.azuretools.sdkmanage.AzureManager;
import com.microsoft.azuretools.utils.AzureUIRefreshCore;
import com.microsoft.azuretools.utils.AzureUIRefreshEvent;
import com.microsoft.azuretools.utils.Pair;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Created by shch on 10/3/2016.
 */
public class SubscriptionManager {
    private final Set<ISubscriptionSelectionListener> listeners = new HashSet<>();
    protected final AzureManager azureManager;

    // for user to select subscription to work with
    private List<SubscriptionDetail> subscriptionDetails; // NOTE: This one should be retired in future.
    private Map<String, SubscriptionDetail> subscriptionIdToSubscriptionDetailMap;
    protected Map<String, Subscription> subscriptionIdToSubscriptionMap = new ConcurrentHashMap<>();

    // to get tid for sid
    private final Map<String, String> sidToTid = new ConcurrentHashMap<>();

    public SubscriptionManager(AzureManager azureManager) {
        this.azureManager = azureManager;
    }

    public synchronized Map<String, SubscriptionDetail> getSubscriptionIdToSubscriptionDetailsMap() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.getSubscriptionIdToSubscriptionDetailsMap()");
        updateSubscriptionDetailsIfNull();
        return subscriptionIdToSubscriptionDetailMap;
    }

    public synchronized Map<String, Subscription> getSubscriptionIdToSubscriptionMap() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.getSubscriptionIdToSubscriptionMap()");
        updateSubscriptionDetailsIfNull();
        return subscriptionIdToSubscriptionMap;
    }

    @AzureOperation(name = "account|subscription.get_details", type = AzureOperation.Type.TASK)
    public synchronized List<SubscriptionDetail> getSubscriptionDetails() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.getSubscriptionDetails()");
        updateSubscriptionDetailsIfNull();
        return subscriptionDetails;
    }

    @AzureOperation(name = "account|subscription.get_detail.selected", type = AzureOperation.Type.TASK)
    public synchronized List<SubscriptionDetail> getSelectedSubscriptionDetails() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.getSelectedSubscriptionDetails()");
        updateSubscriptionDetailsIfNull();

        return getAccountSidList().stream()
                .map(sid -> subscriptionIdToSubscriptionDetailMap.get(sid))
                .filter(s -> Objects.nonNull(s) && s.isSelected())
                .collect(Collectors.toList());
    }

    public void updateSubscriptionDetailsIfNull() {
        if (subscriptionDetails == null) {
            List<SubscriptionDetail> sdl = updateAccountSubscriptionList();
            doSetSubscriptionDetails(sdl);
        }
    }

    @AzureOperation(name = "account|subscription.flush_cache", type = AzureOperation.Type.SERVICE)
    protected List<SubscriptionDetail> updateAccountSubscriptionList() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.updateAccountSubscriptionList()");

        if (azureManager == null) {
            throw new IllegalArgumentException("azureManager is null");
        }

        System.out.println("Getting subscription list from Azure");
        subscriptionIdToSubscriptionMap.clear();
        List<SubscriptionDetail> sdl = new ArrayList<>();
        List<String> selectedSubscriptionIds = azureManager.getSelectedSubscriptionIds();
        List<Pair<Subscription, Tenant>> stpl = azureManager.getSubscriptionsWithTenant();
        for (Pair<Subscription, Tenant> stp : stpl) {
            sdl.add(new SubscriptionDetail(
                    stp.first().subscriptionId(),
                    stp.first().displayName(),
                    stp.second().tenantId(),
                    selectedSubscriptionIds.contains(stp.first().subscriptionId()) || CollectionUtils.isEmpty(selectedSubscriptionIds)));
            // WORKAROUND: update sid->subscription map at the same time
            subscriptionIdToSubscriptionMap.put(stp.first().subscriptionId(), stp.first());
        }
        return sdl;
    }

    private synchronized void doSetSubscriptionDetails(List<SubscriptionDetail> subscriptionDetails) {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.doSetSubscriptionDetails()");
        this.subscriptionDetails = subscriptionDetails;
        updateMapAccordingToList(); // WORKAROUND: Update SubscriptionId->SubscriptionDetail Map
        updateSidToTidMap();
    }

    // WORKAROUND: private helper to construct SubscriptionId->SubscriptionDetail map
    private void updateMapAccordingToList() {
        Map<String, SubscriptionDetail> sid2sd = new ConcurrentHashMap<>();
        for (SubscriptionDetail sd : this.subscriptionDetails) {
            sid2sd.put(sd.getSubscriptionId(),
                    new SubscriptionDetail(
                            sd.getSubscriptionId(),
                            sd.getSubscriptionName(),
                            sd.getTenantId(),
                            sd.isSelected()));
        }
        this.subscriptionIdToSubscriptionDetailMap = sid2sd;
    }

    public void setSubscriptionDetails(List<SubscriptionDetail> subscriptionDetails) {
        System.out.println("SubscriptionManager.setSubscriptionDetails() " + Thread.currentThread().getId());
        doSetSubscriptionDetails(subscriptionDetails);
        notifyAllListeners();
    }

    public synchronized void addListener(ISubscriptionSelectionListener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public synchronized void removeListener(ISubscriptionSelectionListener l) {
        listeners.remove(l);
    }

    private void notifyAllListeners() {
        for (ISubscriptionSelectionListener l : listeners) {
            l.update(subscriptionDetails == null);
        }
        if (AzureUIRefreshCore.listeners != null) {
            AzureUIRefreshCore.execute(new AzureUIRefreshEvent(AzureUIRefreshEvent.EventType.UPDATE, null));
        }
    }

    public synchronized String getSubscriptionTenant(String sid) {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.getSubscriptionTenant()");
        return sidToTid.get(sid);
    }

    public synchronized Set<String> getAccountSidList() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.getAccountSidList()");
        return sidToTid.keySet();
    }

    public void cleanSubscriptions() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.cleanSubscriptions()");
        synchronized (this) {
            if (subscriptionDetails != null) {
                subscriptionDetails.clear();
                subscriptionDetails = null;
                sidToTid.clear();
            }
        }
        notifyAllListeners();
    }

    private void updateSidToTidMap() {
        System.out.println(Thread.currentThread().getId() + " SubscriptionManager.updateSidToTidMap()");
        sidToTid.clear();
        for (SubscriptionDetail sd : subscriptionDetails) {
            sidToTid.put(sd.getSubscriptionId(), sd.getTenantId());
        }
    }
}

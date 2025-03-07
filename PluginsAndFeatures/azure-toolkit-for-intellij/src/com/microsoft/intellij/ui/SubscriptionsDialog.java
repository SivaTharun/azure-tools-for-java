/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.microsoft.azuretools.authmanage.AuthMethodManager;
import com.microsoft.azuretools.authmanage.SubscriptionManager;
import com.microsoft.azuretools.authmanage.models.SubscriptionDetail;
import com.microsoft.azuretools.sdkmanage.IdentityAzureManager;
import com.microsoft.intellij.actions.SelectSubscriptionsAction;
import com.microsoft.intellij.util.JTableUtils;
import com.microsoft.azuretools.sdkmanage.AzureManager;
import com.microsoft.azuretools.telemetry.AppInsightsClient;
import com.microsoft.azuretools.telemetrywrapper.EventType;
import com.microsoft.azuretools.telemetrywrapper.EventUtil;
import com.microsoft.intellij.ui.components.AzureDialogWrapper;
import com.microsoft.intellij.util.PluginUtil;
import com.microsoft.tooling.msservices.components.DefaultLoader;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.microsoft.azuretools.telemetry.TelemetryConstants.ACCOUNT;
import static com.microsoft.azuretools.telemetry.TelemetryConstants.GET_SUBSCRIPTIONS;
import static com.microsoft.azuretools.telemetry.TelemetryConstants.SELECT_SUBSCRIPTIONS;

public class SubscriptionsDialog extends AzureDialogWrapper {
    private static final int CHECKBOX_COLUMN = 0;
    private static final Logger LOGGER = Logger.getInstance(SubscriptionsDialog.class);
    private final Project project;
    private JPanel contentPane;
    private JPanel panelTable;
    private JTable table;
    private List<SubscriptionDetail> sdl;

    private SubscriptionsDialog(List<SubscriptionDetail> sdl, Project project) {
        super(project, true, IdeModalityType.PROJECT);
        this.sdl = sdl;
        this.project = project;
        setModal(true);
        setTitle("Select Subscriptions");
        setOKButtonText("Select");

        setSubscriptions();

        init();
    }

    /**
     * Open select-subscription dialog.
     */
    public static SubscriptionsDialog go(List<SubscriptionDetail> sdl, Project project) {
        if (CollectionUtils.isEmpty(sdl)) {
            PluginUtil.displayInfoDialog("No subscription", "No subscription in current account, you may get a free "
                    + "one from https://azure.microsoft.com/en-us/free/");
            return null;
        }
        SubscriptionsDialog d = new SubscriptionsDialog(sdl, project);
        d.show();
        if (d.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
            return d;
        }

        return null;
    }

    public List<SubscriptionDetail> getSubscriptionDetails() {
        return sdl;
    }

    @NotNull
    @Override
    protected Action[] createActions() {
        return new Action[]{this.getOKAction(), this.getCancelAction()};
    }

    private void refreshSubscriptions() {
        EventUtil.executeWithLog(ACCOUNT, GET_SUBSCRIPTIONS, (operation) -> {
            AzureManager manager = AuthMethodManager.getInstance().getAzureManager();
            if (manager == null) {
                return;
            }
            final SubscriptionManager subscriptionManager = manager.getSubscriptionManager();
            subscriptionManager.cleanSubscriptions();

            DefaultTableModel dm = (DefaultTableModel) table.getModel();
            dm.getDataVector().removeAllElements();
            dm.fireTableDataChanged();

            SelectSubscriptionsAction.loadSubscriptions(subscriptionManager, project);

            //System.out.println("refreshSubscriptions: calling getSubscriptionDetails()");
            sdl = subscriptionManager.getSubscriptionDetails();
            setSubscriptions();
            // to notify subscribers
            subscriptionManager.setSubscriptionDetails(sdl);

        }, (ex) -> {
                ex.printStackTrace();
                //LOGGER.error("refreshSubscriptions", ex);
                ErrorWindow.show(project, ex.getMessage(), "Refresh Subscriptions Error");
            });
    }

    private void setSubscriptions() {
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        for (SubscriptionDetail sd : sdl) {
            model.addRow(new Object[]{sd.isSelected(), sd.getSubscriptionName(), sd.getSubscriptionId()});
        }
        model.fireTableDataChanged();
    }

    private void createUIComponents() {
        DefaultTableModel model = new SubscriptionTableModel();
        model.addColumn("Selected"); // Set the text read by JAWS
        model.addColumn("Subscription name");
        model.addColumn("Subscription ID");

        table = new JBTable();
        table.setModel(model);
        TableColumn column = table.getColumnModel().getColumn(CHECKBOX_COLUMN);
        column.setHeaderValue(""); // Don't show title text
        column.setMinWidth(23);
        column.setMaxWidth(23);
        JTableUtils.enableBatchSelection(table, CHECKBOX_COLUMN);
        table.getTableHeader().setReorderingAllowed(false);
        new TableSpeedSearch(table);

        AnActionButton refreshAction = new AnActionButton("Refresh", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(AnActionEvent anActionEvent) {
                AppInsightsClient.createByType(AppInsightsClient.EventType.Subscription, "", "Refresh", null);
                refreshSubscriptions();
            }
        };

        ToolbarDecorator tableToolbarDecorator =
                ToolbarDecorator.createDecorator(table)
                        .disableUpDownActions()
                        .addExtraActions(refreshAction);

        panelTable = tableToolbarDecorator.createPanel();

    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return contentPane;
    }

    @Override
    protected void doOKAction() {
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        int rc = model.getRowCount();
        int unselectedCount = 0;
        for (int ri = 0; ri < rc; ++ri) {
            boolean selected = (boolean) model.getValueAt(ri, CHECKBOX_COLUMN);
            if (!selected) {
                unselectedCount++;
            }
        }

        if (rc != 0 && unselectedCount == rc) {
            DefaultLoader.getUIHelper().showMessageDialog(
                    contentPane, "Please select at least one subscription",
                    "Subscription dialog info", Messages.getInformationIcon());
            return;
        }

        for (int ri = 0; ri < rc; ++ri) {
            boolean selected = (boolean) model.getValueAt(ri, CHECKBOX_COLUMN);
            this.sdl.get(ri).setSelected(selected);
        }

        List<String> selectedIds = this.sdl.stream().filter(SubscriptionDetail::isSelected).map(SubscriptionDetail::getSubscriptionId).collect(Collectors.toList());
        IdentityAzureManager.getInstance().selectSubscriptionByIds(selectedIds);

        final Map<String, String> properties = new HashMap<>();
        properties.put("subsCount", String.valueOf(rc));
        properties.put("selectedSubsCount", String.valueOf(rc - unselectedCount));
        EventUtil.logEvent(EventType.info, ACCOUNT, SELECT_SUBSCRIPTIONS, null);
        super.doOKAction();
    }

    @Nullable
    @Override
    protected String getDimensionServiceKey() {
        return "SubscriptionsDialog";
    }

    private class SubscriptionTableModel extends DefaultTableModel {
        final Class[] columnClass = new Class[]{
            Boolean.class, String.class, String.class
        };

        @Override
        public boolean isCellEditable(int row, int col) {
            return (col == CHECKBOX_COLUMN);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnClass[columnIndex];
        }
    }

}

package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.util.StringConverter;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountSavedTimes;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 网站账号与秘密输入区；网站选择由父页拥有，异步回执仅在原 scope 的 FX epoch 内应用，草稿不进入 ViewSchema 数据。 */
final class SiteAccountSettingsSection {
    private static final DateTimeFormatter SAVED_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z");
    private final ExtensionSettingsGateway gateway;
    private final Runnable refreshViews;
    private final CanonicalJson json = new CanonicalJson();
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final FormSection root = new FormSection("网站账号", "每个账号分别保存密码和登录态；密码提交后立即清空，不可回读。");
    private final ComboBox<AccountProjection> accounts = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox enabled = new CheckBox("启用账号");
    private final CheckBox confirmDelete = new CheckBox("确认永久删除所选账号及其密码、登录态");
    private final Label status = new Label();
    private final Label savedTimes = new Label();
    private final Button refresh;
    private final Button create;
    private final Button save;
    private final Button secret;
    private final Button makeDefault;
    private final Button logout;
    private final Button delete;
    private Optional<WorkspaceId> workspace = Optional.empty();
    private Optional<SiteContracts.Projection> site = Optional.empty();
    private Map<String, AccountSavedTimes> accountSavedTimes = Map.of();
    private BooleanSupplier externalDirty = () -> false;
    private BooleanSupplier externalPending = () -> false;
    private BooleanSupplier allowSelectionChange = () -> !dirty();
    private Runnable stateChanged = () -> {};
    private CompletableFuture<?> loading;
    private long epoch;
    private boolean active;
    private boolean disposed;
    private boolean cacheValid;
    private boolean busy;
    private boolean rendering;
    private boolean metadataDirty;

    SiteAccountSettingsSection(ExtensionSettingsGateway gateway, Runnable refreshViews) {
        this.gateway = gateway;
        this.refreshViews = refreshViews;
        refresh = button("刷新账号", ActionStyle.GHOST, this::refreshAccounts);
        create = button("创建账号", ActionStyle.SOFT, this::create);
        save = button("保存账号", ActionStyle.SOFT, this::save);
        secret = button("保存用户名密码", ActionStyle.PRIMARY, this::saveSecret);
        makeDefault = button("设为默认", ActionStyle.SOFT, () -> control("account/default"));
        logout = button("注销登录态", ActionStyle.SOFT, () -> control("account/logout"));
        delete = button("删除账号", ActionStyle.SOFT, () -> control("account/delete"));
        configure();
    }

    Node content() {
        return root;
    }

    void activate() {
        if (!disposed) {
            active = true;
            if (!cacheValid && !busy && !dirty()) {
                reloadAccounts();
            }
            updateActions();
        }
    }

    void workspaceChanged(Optional<Workspace> next) {
        invalidateRequests();
        workspace = next.map(Workspace::id);
        site = Optional.empty();
        clearAccounts();
    }

    /** 父页在完成整体草稿确认后传入权威选择；同一网站的版本刷新保留本地草稿。 */
    void setSite(Optional<SiteContracts.Projection> next) {
        Objects.requireNonNull(next, "next");
        boolean sameSite = site.map(SiteContracts.Projection::id).equals(next.map(SiteContracts.Projection::id));
        site = next;
        if (!sameSite) {
            invalidateRequests();
            clearAccounts();
            if (active && !disposed) {
                reloadAccounts();
            }
        }
        updateActions();
    }

    /** 父页提供其他区的状态及统一放弃确认；返回 false 时账号选择和所有字段保持原值。 */
    void setContextGuard(BooleanSupplier dirty, BooleanSupplier pending, BooleanSupplier selectionChange) {
        externalDirty = Objects.requireNonNull(dirty, "dirty");
        externalPending = Objects.requireNonNull(pending, "pending");
        allowSelectionChange = Objects.requireNonNull(selectionChange, "selectionChange");
        updateActions();
    }

    void setStateChanged(Runnable listener) {
        stateChanged = Objects.requireNonNull(listener, "listener");
    }

    void refreshContext() {
        updateActions();
    }

    void invalidateCache() {
        cacheValid = false;
    }

    void deactivate() {
        active = false;
        invalidateRequests();
        discardDraft();
    }

    void dispose() {
        disposed = true;
        active = false;
        invalidateRequests();
        workspace = Optional.empty();
        site = Optional.empty();
        clearAccounts();
        stateChanged = () -> {};
    }

    private void invalidateRequests() {
        // 先推进 epoch，再取消只读 Future，避免同步完成回调把取消显示成新作用域的错误。
        epoch++;
        CompletableFuture<?> previous = loading;
        loading = null;
        busy = false;
        cacheValid = false;
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void clearAccounts() {
        accountSavedTimes = Map.of();
        rendering = true;
        accounts.getItems().clear();
        accounts.setValue(null);
        rendering = false;
        selectAccount(null);
    }

    boolean dirty() {
        return metadataDirty || secretDirty() || confirmDelete.isSelected();
    }

    boolean pending() {
        return busy;
    }

    void discardDraft() {
        username.clear();
        password.clear();
        confirmDelete.setSelected(false);
        metadataDirty = false;
        selectAccount(accounts.getValue());
    }

    void warnUnsavedChanges() {
        status.setText("账号或密码尚未提交；离开页面会清空本地秘密输入");
    }

    private void configure() {
        accounts.setConverter(converter(account -> account.name() + (account.defaultAccount() ? " · 默认" : "")));
        accounts.setMaxWidth(Double.MAX_VALUE);
        accounts.setAccessibleText("所选网站的账号");
        name.setPromptText("例如：工作账号；创建时作为新账号名称");
        username.setPromptText("输入用户名；已有用户名不从密钥库回读");
        password.setPromptText("输入新密码；提交后立即清空");
        status.setWrapText(true);
        savedTimes.setWrapText(true);
        root.addField("账号", accounts);
        root.addField("账号名称", name);
        root.addFullWidth(enabled);
        root.addFullWidth(new FlowPane(8, 8, refresh, create, save, makeDefault, logout));
        root.addField("用户名", username);
        root.addField("密码", password);
        root.addFullWidth(secret);
        root.addFullWidth(savedTimes);
        root.addFullWidth(status);
        root.addFullWidth(confirmDelete);
        root.addFullWidth(delete);
        configureListeners();
        updateActions();
    }

    private void configureListeners() {
        accounts.valueProperty().addListener((ignored, previous, next) -> {
            if (!rendering && !Objects.equals(previous, next)) {
                if (busy || externalPending.getAsBoolean() || !allowSelectionChange.getAsBoolean()) {
                    rendering = true;
                    accounts.setValue(previous);
                    rendering = false;
                    updateActions();
                    return;
                }
                epoch++;
                selectAccount(next);
            }
        });
        name.textProperty().addListener((ignored, previous, next) -> metadataChanged());
        enabled.selectedProperty().addListener((ignored, previous, next) -> metadataChanged());
        username.textProperty().addListener((ignored, previous, next) -> updateActions());
        password.textProperty().addListener((ignored, previous, next) -> updateActions());
        confirmDelete.selectedProperty().addListener((ignored, previous, next) -> updateActions());
    }

    private void metadataChanged() {
        if (!rendering) {
            AccountProjection account = accounts.getValue();
            metadataDirty = account == null
                    ? !name.getText().isEmpty() || !enabled.isSelected()
                    : !name.getText().equals(account.name()) || enabled.isSelected() != account.enabled();
        }
        updateActions();
    }

    private void selectAccount(AccountProjection account) {
        rendering = true;
        name.setText(account == null ? "" : account.name());
        enabled.setSelected(account == null || account.enabled());
        username.clear();
        password.clear();
        confirmDelete.setSelected(false);
        metadataDirty = false;
        rendering = false;
        renderSavedTimes(account);
        if (account != null) {
            status.setText((account.passwordConfigured() ? "用户名密码已保存" : "尚未保存密码") + "；"
                    + (account.loginStateConfigured() ? "登录态已保存" : "尚未保存登录态"));
        } else {
            status.setText(site.isPresent() ? "当前网站暂无账号，可输入名称创建" : "选择网站后管理账号");
        }
        updateActions();
    }

    private void renderSavedTimes(AccountProjection account) {
        AccountSavedTimes times = account == null ? null : accountSavedTimes.get(account.accountId());
        savedTimes.setText(
                account == null
                        ? ""
                        : "密码保存时间："
                                + savedTime(
                                        times == null ? Optional.empty() : times.passwordSavedAt(),
                                        account.passwordConfigured())
                                + "\n登录态保存时间："
                                + savedTime(
                                        times == null ? Optional.empty() : times.loginStateSavedAt(),
                                        account.loginStateConfigured()));
        savedTimes.setVisible(account != null);
        savedTimes.setManaged(account != null);
    }

    private static String savedTime(Optional<Instant> value, boolean configured) {
        return value.map(time -> SAVED_TIME.format(time.atZone(ZoneId.systemDefault())))
                .orElse(configured ? "暂未提供" : "尚未保存");
    }

    private void refreshAccounts() {
        if (!busy && !externalPending.getAsBoolean() && allowSelectionChange.getAsBoolean()) {
            reloadAccounts();
        }
    }

    private void reloadAccounts() {
        if (!active || disposed || workspace.isEmpty() || site.isEmpty()) {
            updateActions();
            return;
        }
        if (dirty()) {
            return;
        }
        String siteId = site.orElseThrow().id();
        long requestEpoch = begin("正在读取账号…");
        try {
            CompletableFuture<ExtensionRpcContracts.CallResult> request = gateway.query(
                    workspace.orElseThrow(),
                    BuiltinExtensionIds.SITE,
                    "account/list",
                    json.encode(new SiteAccountContracts.ListRequest(siteId)));
            loading = request;
            request.whenComplete((result, failure) ->
                    FxStateDispatcher.dispatch(() -> completeAccounts(requestEpoch, siteId, result, failure)));
        } catch (RuntimeException failure) {
            complete(requestEpoch, failure);
        }
    }

    private void completeAccounts(
            long requestEpoch, String siteId, ExtensionRpcContracts.CallResult result, Throwable failure) {
        if (!current(requestEpoch)) {
            return;
        }
        loading = null;
        if (failure != null) {
            complete(requestEpoch, failure);
            return;
        }
        try {
            var accountList = json.decode(result.payload(), SiteAccountContracts.AccountList.class);
            var loaded = accountList.accounts();
            if (loaded.stream().anyMatch(account -> !account.siteId().equals(siteId))) {
                throw new IllegalStateException("账号查询返回了其他网站的数据");
            }
            String previous =
                    accounts.getValue() == null ? "" : accounts.getValue().accountId();
            accountSavedTimes = accountList.savedTimes();
            rendering = true;
            accounts.getItems().setAll(loaded);
            accounts.setValue(loaded.stream()
                    .filter(account -> account.accountId().equals(previous))
                    .findFirst()
                    .orElse(loaded.isEmpty() ? null : loaded.getFirst()));
            rendering = false;
            cacheValid = true;
            busy = false;
            selectAccount(accounts.getValue());
        } catch (RuntimeException invalid) {
            rendering = false;
            complete(requestEpoch, invalid);
        }
    }

    private void create() {
        if (!create.isDisabled()) {
            mutate("account/create", Map.of("siteId", site.orElseThrow().id(), "name", name.getText()), 0);
        }
    }

    private void save() {
        if (save.isDisabled()) {
            return;
        }
        AccountProjection account = accounts.getValue();
        mutate(
                "account/update",
                Map.of("selection", selection(account), "name", name.getText(), "enabled", enabled.isSelected()),
                account.revision());
    }

    private void control(String operation) {
        Button action =
                switch (operation) {
                    case "account/default" -> makeDefault;
                    case "account/logout" -> logout;
                    case "account/delete" -> delete;
                    default -> throw new IllegalArgumentException("不支持的账号操作");
                };
        if (!action.isDisabled()) {
            AccountProjection account = accounts.getValue();
            mutate(operation, selection(account), account.revision());
        }
    }

    private void mutate(String operation, Map<String, Object> arguments, long revision) {
        WorkspaceId scope = workspace.orElseThrow();
        long requestEpoch = begin("正在保存账号…");
        var request = new ViewCommandInvocation(operation, arguments, revision, operation.equals("account/delete"));
        try {
            finishMutation(requestEpoch, gateway.execute(scope, BuiltinExtensionIds.SITE, request));
        } catch (RuntimeException failure) {
            complete(requestEpoch, failure);
        }
    }

    private void saveSecret() {
        if (secret.isDisabled()) {
            return;
        }
        AccountProjection account = accounts.getValue();
        WorkspaceId scope = workspace.orElseThrow();
        char[] user = username.getText().toCharArray();
        char[] pass = password.getText().toCharArray();
        username.clear();
        password.clear();
        long requestEpoch = begin("正在密封并保存用户名密码…");
        try {
            var request = new SiteAccountContracts.CredentialRequest(
                    new SiteAccountContracts.Selection(account.siteId(), account.accountId()),
                    account.securityRevision(),
                    site.orElseThrow().authorityRevision());
            finishMutation(
                    requestEpoch,
                    gateway.setAccountCredential(
                            scope, request, user, pass, CommandOptions.create(account.revision())));
        } catch (RuntimeException failure) {
            complete(requestEpoch, failure);
        } finally {
            Arrays.fill(user, '\0');
            Arrays.fill(pass, '\0');
        }
    }

    private void finishMutation(long requestEpoch, CompletableFuture<?> future) {
        future.whenComplete((result, failure) -> FxStateDispatcher.dispatch(() -> {
            if (complete(requestEpoch, failure)) {
                metadataDirty = false;
                confirmDelete.setSelected(false);
                cacheValid = false;
                refreshViews.run();
                reloadAccounts();
            }
        }));
    }

    private long begin(String message) {
        busy = true;
        status.setText(message);
        updateActions();
        return ++epoch;
    }

    private boolean complete(long requestEpoch, Throwable failure) {
        if (!current(requestEpoch)) {
            return false;
        }
        busy = false;
        if (failure != null) {
            status.setText(SettingsFailures.message(failure));
        }
        updateActions();
        return failure == null;
    }

    private boolean current(long requestEpoch) {
        return requestEpoch == epoch && active && !disposed;
    }

    private boolean secretDirty() {
        return !username.getText().isEmpty() || !password.getText().isEmpty();
    }

    private void updateActions() {
        boolean blocked = busy || externalPending.getAsBoolean();
        boolean available = active && !disposed && workspace.isPresent() && site.isPresent() && !blocked;
        boolean selected = available && accounts.getValue() != null;
        boolean otherDraft = externalDirty.getAsBoolean();
        boolean secretDraft = secretDirty();
        accounts.setDisable(!available);
        refresh.setDisable(!available);
        updateMetadataActions(available && !otherDraft, selected && !otherDraft, secretDraft);
        updateAccountActions(selected && !otherDraft, secretDraft);
        updateInputs(available, selected, secretDraft);
        stateChanged.run();
    }

    private void updateMetadataActions(boolean available, boolean selected, boolean secretDraft) {
        boolean blocked = secretDraft || confirmDelete.isSelected();
        create.setDisable(!available || blocked || name.getText().isBlank());
        save.setDisable(!selected || blocked || !metadataDirty || name.getText().isBlank());
    }

    private void updateInputs(boolean available, boolean selected, boolean secretDraft) {
        boolean metadataBlocked = !available || secretDraft || confirmDelete.isSelected();
        boolean secretBlocked = !selected || metadataDirty || confirmDelete.isSelected();
        name.setDisable(metadataBlocked);
        enabled.setDisable(metadataBlocked);
        username.setDisable(secretBlocked);
        password.setDisable(secretBlocked);
        confirmDelete.setDisable(!selected || externalDirty.getAsBoolean() || metadataDirty || secretDraft);
    }

    private void updateAccountActions(boolean selected, boolean secretDraft) {
        boolean controlAllowed = selected && !metadataDirty && !secretDraft;
        boolean ordinaryControl = controlAllowed && !confirmDelete.isSelected();
        makeDefault.setDisable(!ordinaryControl || !accounts.getValue().enabled());
        logout.setDisable(!ordinaryControl || !accounts.getValue().loginStateConfigured());
        delete.setDisable(!controlAllowed || !confirmDelete.isSelected());
        secret.setDisable(!selected
                || metadataDirty
                || confirmDelete.isSelected()
                || username.getText().isEmpty()
                || password.getText().isEmpty());
    }

    private Button button(String label, ActionStyle style, Runnable operation) {
        Button button = components.action(label, style, ActionSize.NORMAL);
        button.setOnAction(event -> operation.run());
        return button;
    }

    private static Map<String, Object> selection(AccountProjection account) {
        return Map.of("siteId", account.siteId(), "accountId", account.accountId());
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String value) {
                throw new UnsupportedOperationException("账号选择不接受自由输入");
            }
        };
    }
}

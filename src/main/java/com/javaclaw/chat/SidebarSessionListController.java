package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Coordinates session filtering, grouping, selection and batch actions.
 *
 * <p>The controller owns page state only. Persistence and workspace effects are emitted
 * through callbacks into the parent application controller. List cells are virtualized and
 * each cell loads its FXML exactly once.</p>
 */
public final class SidebarSessionListController
        implements SidebarSessionCellActions, AutoCloseable {

    private final SpringFxmlLoader fxmlLoader;
    private final SidebarViewModel viewModel = new SidebarViewModel();
    private final List<SessionState> sessions = new ArrayList<>();
    private final Set<String> checkedSessionIds = new HashSet<>();
    private final Set<SidebarSessionListCell> cells =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @FXML private VBox root;
    @FXML private ListView<SidebarSessionItem> sessionList;
    @FXML private Button manageButton;
    @FXML private Button batchDeleteButton;
    @FXML private Button selectAllButton;
    @FXML private HBox batchDeleteRow;
    @FXML private TextField searchField;
    @FXML private Label sessionCountLabel;
    @FXML private VBox emptyState;
    @FXML private Label emptyTitle;
    @FXML private Label emptyHint;

    private ChangeListener<String> searchListener;
    private Consumer<String> onSwitchSession;
    private Consumer<String> onDeleteSession;
    private Consumer<List<String>> onBatchDeleteSessions;
    private boolean closed;

    @Autowired
    public SidebarSessionListController(SpringFxmlLoader fxmlLoader) {
        this.fxmlLoader = Objects.requireNonNull(fxmlLoader, "fxmlLoader");
    }

    @FXML
    private void initialize() {
        sessionList.setCellFactory(list -> {
            SidebarSessionListCell cell = new SidebarSessionListCell(fxmlLoader, this);
            cells.add(cell);
            return cell;
        });
        searchListener = (observable, previous, query) -> {
            viewModel.searchQueryProperty().set(query == null ? "" : query);
            refreshProjection();
        };
        searchField.textProperty().addListener(searchListener);
        refreshProjection();
    }

    public VBox getRoot() {
        return root;
    }

    public void addSession(ChatSession session, boolean selected) {
        Objects.requireNonNull(session, "session");
        sessions.add(SessionState.from(session));
        if (selected) viewModel.selectedSessionIdProperty().set(session.getId());
        refreshProjection();
    }

    public void insertSessionAtTop(ChatSession session, boolean selected) {
        Objects.requireNonNull(session, "session");
        sessions.addFirst(SessionState.from(session));
        if (selected) viewModel.selectedSessionIdProperty().set(session.getId());
        refreshProjection();
    }

    public void updateSessionTitle(String sessionId, String newTitle) {
        for (int index = 0; index < sessions.size(); index++) {
            SessionState current = sessions.get(index);
            if (current.id().equals(sessionId)) {
                sessions.set(index, current.withTitle(normalizedTitle(newTitle)));
                refreshProjection();
                return;
            }
        }
    }

    public void removeSession(String sessionId) {
        sessions.removeIf(session -> session.id().equals(sessionId));
        checkedSessionIds.remove(sessionId);
        refreshProjection();
    }

    public void selectSession(String sessionId) {
        if (sessions.stream().anyMatch(session -> session.id().equals(sessionId))) {
            viewModel.selectedSessionIdProperty().set(sessionId);
            refreshProjection();
        }
    }

    public void clearSessions() {
        sessions.clear();
        checkedSessionIds.clear();
        viewModel.selectedSessionIdProperty().set(null);
        if (!searchField.getText().isEmpty()) searchField.clear();
        refreshProjection();
    }

    public String getSelectedSessionId() {
        return viewModel.selectedSessionIdProperty().get();
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public void setOnSwitchSession(Consumer<String> callback) {
        onSwitchSession = callback;
    }

    public void setOnDeleteSession(Consumer<String> callback) {
        onDeleteSession = callback;
    }

    public void setOnBatchDeleteSessions(Consumer<List<String>> callback) {
        onBatchDeleteSessions = callback;
    }

    @FXML
    private void toggleBatchMode() {
        boolean enabled = !viewModel.batchModeProperty().get();
        viewModel.batchModeProperty().set(enabled);
        checkedSessionIds.clear();
        manageButton.setText(enabled ? "完成" : "管理");
        batchDeleteRow.setVisible(enabled);
        batchDeleteRow.setManaged(enabled);
        updateBatchButtons();
        refreshProjection();
    }

    @FXML
    private void onToggleSelectAll() {
        if (checkedSessionIds.size() < sessions.size()) {
            sessions.stream().map(SessionState::id).forEach(checkedSessionIds::add);
        } else {
            checkedSessionIds.clear();
        }
        updateBatchButtons();
        refreshProjection();
    }

    @FXML
    private void onBatchDelete() {
        if (checkedSessionIds.isEmpty()) return;
        Alert alert = UIHelper.createConfirmAlert(
                "确认批量删除",
                "确定要删除选中的 " + checkedSessionIds.size() + " 个会话吗？\n此操作不可恢复。",
                null);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ignored -> {
            List<String> selected = List.copyOf(checkedSessionIds);
            if (onBatchDeleteSessions != null) onBatchDeleteSessions.accept(selected);
            viewModel.batchModeProperty().set(false);
            checkedSessionIds.clear();
            manageButton.setText("管理");
            batchDeleteRow.setVisible(false);
            batchDeleteRow.setManaged(false);
            updateBatchButtons();
            refreshProjection();
        });
    }

    @Override
    public void activate(String sessionId) {
        if (viewModel.batchModeProperty().get()) {
            checked(sessionId, !checkedSessionIds.contains(sessionId));
            return;
        }
        if (!Objects.equals(sessionId, viewModel.selectedSessionIdProperty().get())) {
            viewModel.selectedSessionIdProperty().set(sessionId);
            refreshProjection();
            if (onSwitchSession != null) onSwitchSession.accept(sessionId);
        }
    }

    @Override
    public void delete(String sessionId) {
        if (onDeleteSession != null) onDeleteSession.accept(sessionId);
    }

    @Override
    public void checked(String sessionId, boolean selected) {
        if (selected) checkedSessionIds.add(sessionId);
        else checkedSessionIds.remove(sessionId);
        updateBatchButtons();
        refreshProjection();
    }

    private void updateBatchButtons() {
        int selected = checkedSessionIds.size();
        int total = sessions.size();
        batchDeleteButton.setText(selected > 0 ? "删除选中（" + selected + "）" : "删除选中");
        batchDeleteButton.setDisable(selected == 0);
        selectAllButton.setText(selected >= total && total > 0 ? "取消全选" : "全选");
    }

    private void refreshProjection() {
        List<SessionState> visible = filteredSessions();
        List<SidebarSessionItem> projection = new ArrayList<>();
        for (int start = 0; start < visible.size();) {
            String group = groupKey(visible.get(start).createdAt());
            int end = start + 1;
            while (end < visible.size() && group.equals(groupKey(visible.get(end).createdAt()))) {
                end++;
            }
            projection.add(new SidebarSessionItem.Group(group, end - start));
            for (int index = start; index < end; index++) {
                SessionState state = visible.get(index);
                projection.add(toProjection(state));
            }
            start = end;
        }
        sessionList.getItems().setAll(projection);
        sessionList.getSelectionModel().clearSelection();
        updateSessionCount(sessions.size());
        updateEmptyState(visible.size());
    }

    private List<SessionState> filteredSessions() {
        String query = viewModel.searchQueryProperty().get();
        if (query == null || query.isBlank()) return List.copyOf(sessions);
        String keyword = query.strip().toLowerCase(Locale.ROOT);
        return sessions.stream()
                .filter(session -> session.title().toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
    }

    private SidebarSessionItem.Conversation toProjection(SessionState state) {
        boolean batchMode = viewModel.batchModeProperty().get();
        return new SidebarSessionItem.Conversation(
                state.id(), state.title(), state.timeText(),
                !batchMode && state.id().equals(viewModel.selectedSessionIdProperty().get()),
                batchMode, checkedSessionIds.contains(state.id()));
    }

    private void updateEmptyState(int visibleSessions) {
        boolean searching = !viewModel.searchQueryProperty().get().isBlank();
        boolean show = sessions.isEmpty() || (searching && visibleSessions == 0);
        if (sessions.isEmpty()) {
            emptyTitle.setText("还没有会话");
            emptyHint.setText("点击上方「新建对话」开始");
        } else if (show) {
            emptyTitle.setText("没有匹配的会话");
            emptyHint.setText("换个关键词试试");
        }
        emptyState.setVisible(show);
        emptyState.setManaged(show);
    }

    public void updateSessionCount(int count) {
        int normalized = Math.max(0, count);
        viewModel.sessionCountProperty().set(normalized);
        sessionCountLabel.setText(normalized > 0 ? normalized + " 个" : "");
    }

    private static String normalizedTitle(String title) {
        return title == null || title.isBlank() ? "新的对话" : title.strip();
    }

    private static String groupKey(LocalDateTime createdAt) {
        LocalDate today = LocalDate.now();
        LocalDate date = createdAt == null ? today : createdAt.toLocalDate();
        if (date.equals(today)) return "今天";
        if (date.equals(today.minusDays(1))) return "昨天";
        if (date.isAfter(today.minusDays(7))) return "本周";
        return "更早";
    }

    private static String formatTime(LocalDateTime createdAt) {
        if (createdAt == null) return "";
        LocalDate today = LocalDate.now();
        LocalDate date = createdAt.toLocalDate();
        if (date.equals(today)) {
            return String.format("%02d:%02d", createdAt.getHour(), createdAt.getMinute());
        }
        if (date.equals(today.minusDays(1))) return "昨天";
        if (date.isAfter(today.minusDays(7))) {
            return switch (date.getDayOfWeek()) {
                case MONDAY -> "周一";
                case TUESDAY -> "周二";
                case WEDNESDAY -> "周三";
                case THURSDAY -> "周四";
                case FRIDAY -> "周五";
                case SATURDAY -> "周六";
                case SUNDAY -> "周日";
            };
        }
        return String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        searchField.textProperty().removeListener(searchListener);
        sessionList.setCellFactory(null);
        sessionList.getItems().clear();
        List.copyOf(cells).forEach(SidebarSessionListCell::close);
        cells.clear();
        sessions.clear();
        checkedSessionIds.clear();
        onSwitchSession = null;
        onDeleteSession = null;
        onBatchDeleteSessions = null;
    }

    boolean isClosed() {
        return closed;
    }

    private record SessionState(
            String id,
            String title,
            LocalDateTime createdAt,
            String timeText
    ) {
        static SessionState from(ChatSession session) {
            return new SessionState(session.getId(), normalizedTitle(session.getTitle()),
                    session.getCreatedAt(), formatTime(session.getCreatedAt()));
        }

        SessionState withTitle(String value) {
            return new SessionState(id, value, createdAt, timeText);
        }
    }
}

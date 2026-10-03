package dev.modmaker.ui;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.validate.Issue;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import java.util.List;

/** Validation findings for the current project; clicking one selects the record it belongs to. */
public final class IssuesPane extends BorderPane {

    private final ProjectController controller;
    private final ListView<Issue> list = new ListView<>();
    private final Label summary = new Label();

    public IssuesPane(ProjectController controller) {
        this.controller = controller;
        setTop(summary);
        setCenter(list);
        getStyleClass().add("panel");
        summary.getStyleClass().add("muted");

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Issue issue, boolean empty) {
                super.updateItem(issue, empty);
                if (empty || issue == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText((issue.isError() ? "[error] " : "[warn] ") + issue.message());
                getStyleClass().removeAll("issue-error", "issue-warning");
                getStyleClass().add(issue.isError() ? "issue-error" : "issue-warning");
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, issue) -> {
            if (issue == null || issue.contentId() == null || controller.project() == null) {
                return;
            }
            ContentRecord record = controller.project().contentById(issue.contentId());
            if (record != null) {
                controller.select(record);
            }
        });
        refresh();
    }

    public void refresh() {
        if (controller.project() == null) {
            list.setItems(FXCollections.observableArrayList());
            summary.setText("");
            return;
        }
        List<Issue> issues = controller.validate();
        list.setItems(FXCollections.observableArrayList(issues));
        long errors = issues.stream().filter(Issue::isError).count();
        summary.setText(Messages.t("validation.summary", errors, issues.size() - errors));
    }
}

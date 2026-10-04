package com.wagwag.api.task;

import com.wagwag.api.task.TaskService.TaskPage;
import com.wagwag.api.task.TaskService.TaskResponse;
import com.wagwag.api.task.TaskService.NearbyTaskPage;
import com.wagwag.api.task.TaskService.TaskEvent;
import com.wagwag.api.task.TaskService.TaskProfile;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) { this.tasks = tasks; }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskInput input) {
        TaskResponse task = tasks.create(input);
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }

    @GetMapping
    public TaskPage list(@RequestParam(defaultValue = "open") String scope,
                         @RequestParam(defaultValue = "20") int limit,
                         @RequestParam(defaultValue = "0") int page) {
        return tasks.list(scope, limit, page);
    }

    @GetMapping("/nearby")
    public NearbyTaskPage nearby(@RequestParam double latitude, @RequestParam double longitude,
                                @RequestParam(defaultValue = "5000") double radiusMeters,
                                @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "0") int page) {
        return tasks.nearby(latitude, longitude, radiusMeters, limit, page);
    }

    @GetMapping("/profile")
    public TaskProfile profile() { return tasks.profile(); }

    @PutMapping("/availability")
    public TaskProfile availability(@Valid @RequestBody TaskAvailabilityInput input) {
        return tasks.availability(input.acceptingTasks());
    }

    @GetMapping("/{id}")
    public TaskResponse detail(@PathVariable long id) { return tasks.detail(id); }

    @GetMapping("/{id}/history")
    public List<TaskEvent> history(@PathVariable long id) { return tasks.history(id); }

    @PutMapping("/{id}/rating")
    public TaskResponse rate(@PathVariable long id, @Valid @RequestBody TaskRatingInput input) {
        return tasks.rate(id, input);
    }

    @PostMapping("/{id}/accept")
    public TaskResponse accept(@PathVariable long id) { return tasks.accept(id); }

    @PostMapping("/{id}/start")
    public TaskResponse start(@PathVariable long id) { return tasks.start(id); }

    @PostMapping("/{id}/complete")
    public TaskResponse complete(@PathVariable long id) { return tasks.complete(id); }

    @PostMapping("/{id}/cancel")
    public TaskResponse cancel(@PathVariable long id) { return tasks.cancel(id); }
}

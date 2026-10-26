package com.mch.strategy.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mch.strategy.training.CreateRunsRequest;
import com.mch.strategy.training.ModelRunQueryService;
import com.mch.strategy.training.ModelRunService;
import com.mch.strategy.training.ModelRunViews;
import com.mch.strategy.trainset.CreateTrainingSetRequest;
import com.mch.strategy.trainset.TrainingSetQueryService;
import com.mch.strategy.trainset.TrainingSetService;
import com.mch.strategy.trainset.TrainingSetViews;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 학습 데이터 · 실험(모델 학습 · 평가) JSON API */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MlApiController {

    private final TrainingSetService trainingSetService;
    private final TrainingSetQueryService trainingSetQueryService;
    private final ModelRunService runService;
    private final ModelRunQueryService runQueryService;

    // ---------- 학습 데이터 ----------

    @GetMapping("/trainsets")
    public List<TrainingSetViews.Summary> trainingSets() {
        return trainingSetQueryService.list();
    }

    @PostMapping("/trainsets")
    public Map<String, Long> createTrainingSet(@Valid @RequestBody CreateTrainingSetRequest request) {
        return trainingSetService.create(request);
    }

    @GetMapping("/trainsets/{id}")
    public TrainingSetViews.Detail trainingSet(@PathVariable long id) {
        return trainingSetQueryService.detail(id);
    }

    @DeleteMapping("/trainsets/{id}")
    public Map<String, Object> deleteTrainingSet(@PathVariable long id) {
        trainingSetService.delete(id);
        return Map.of("deleted", id);
    }

    // ---------- 실험 ----------

    @PostMapping("/runs")
    public Map<String, Object> createRuns(@Valid @RequestBody CreateRunsRequest request) {
        return runService.create(request);
    }

    @GetMapping("/runs")
    public List<ModelRunViews.Summary> runs(@RequestParam(required = false) Long trainingSetId) {
        return runQueryService.list(trainingSetId);
    }

    @GetMapping("/runs/{id}")
    public ModelRunViews.Detail run(@PathVariable long id) {
        return runQueryService.detail(id);
    }

    @DeleteMapping("/runs/{id}")
    public Map<String, Object> deleteRun(@PathVariable long id) {
        runService.delete(id);
        return Map.of("deleted", id);
    }

    /** 결과 JSON (split = val · test). 아직 없으면 {"empty": true} */
    @GetMapping(value = "/runs/{id}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> result(@PathVariable long id, @RequestParam String split) {
        String json = runQueryService.result(id, split);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json == null ? "{\"empty\":true}" : json);
    }

    @PostMapping("/runs/{id}/test")
    public Map<String, Object> startTest(@PathVariable long id) {
        return runService.startTest(id);
    }

    /** 예측 기록 CSV 내려받기 */
    @GetMapping("/runs/{id}/predictions")
    public ResponseEntity<Resource> predictions(@PathVariable long id, @RequestParam String split) {
        Path file = runQueryService.predictionsFile(id, split);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"run-" + id + "-" + split + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(new FileSystemResource(file));
    }
}

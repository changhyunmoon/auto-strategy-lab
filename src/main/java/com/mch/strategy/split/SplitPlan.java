package com.mch.strategy.split;

import java.time.Instant;

import com.mch.strategy.common.BuildStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 분할 계획: 정답 데이터셋의 행마다 학습 · 검증 · 최종 테스트 소속 또는 제외 사유를 정한 것.
 * 데이터를 세 번 복사하지 않고 행 키(거래쌍 · 판단 시각)와 소속만 v2/splits/{id}/ 에 저장한다.
 */
@Entity
@Table(name = "v2_split_plan")
@Getter
@Setter
@NoArgsConstructor
public class SplitPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BuildStatus status;

    @Column(name = "label_set_id", nullable = false)
    private Long labelSetId;

    /** SplitConfig JSON */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String config;

    @Column(name = "code_version", nullable = false, length = 30)
    private String codeVersion;

    /** SplitSummary JSON (최종 테스트 정답 분포 포함 — 화면에는 열람 요청 때만 보낸다) */
    @Column(columnDefinition = "LONGTEXT")
    private String summary;

    @Column(name = "test_views", nullable = false)
    private int testViews;

    /** 최종 테스트 정답 분포를 연 시각 목록 JSON */
    @Column(name = "test_view_log", columnDefinition = "LONGTEXT")
    private String testViewLog;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}

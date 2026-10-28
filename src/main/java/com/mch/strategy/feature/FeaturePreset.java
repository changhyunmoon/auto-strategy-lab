package com.mch.strategy.feature;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 지표 생성 화면의 「설정 저장」: 데이터 없이 설정만 이름 붙여 둔 것 */
@Entity
@Table(name = "v2_feature_preset")
@Getter
@Setter
@NoArgsConstructor
public class FeaturePreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String config;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

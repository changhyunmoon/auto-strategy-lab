package com.mch.strategy.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 데이터세트의 열 하나. columnIndex 가 dataset_row.feature_values 배열의 위치다. */
@Entity
@Table(name = "dataset_feature")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatasetColumn {

    /** 지표가 아니라 봉 자체의 값(시고저종·거래량)인 열의 feature_code */
    public static final String BAR = "bar";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "feature_code", nullable = false, length = 50)
    private String featureCode;

    @Column(name = "column_name", nullable = false, length = 100)
    private String columnName;

    @Column(name = "column_index", nullable = false)
    private int columnIndex;

    public DatasetColumn(long datasetId, String featureCode, String columnName, int columnIndex) {
        this.datasetId = datasetId;
        this.featureCode = featureCode;
        this.columnName = columnName;
        this.columnIndex = columnIndex;
    }

    public boolean isBar() {
        return BAR.equals(featureCode);
    }
}

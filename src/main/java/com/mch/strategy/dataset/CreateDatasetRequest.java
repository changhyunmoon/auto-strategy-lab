package com.mch.strategy.dataset;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 데이터 가져오기 화면의 「받기」 요청.
 * from·to 는 UTC 날짜이며 to 날짜 하루 전체를 포함한다.
 */
public record CreateDatasetRequest(
        @NotBlank(message = "데이터세트 이름을 입력하세요.") @Size(max = 100) String name,
        @NotEmpty(message = "종목을 하나 이상 고르세요.") List<Long> assetIds,
        @NotNull(message = "시작일을 고르세요.") LocalDate from,
        @NotNull(message = "종료일을 고르세요.") LocalDate to,
        @NotEmpty(message = "가져올 데이터를 하나 이상 고르세요.") List<String> sources,
        @NotEmpty(message = "봉 간격을 하나 이상 고르세요.") List<String> intervals,
        List<String> features
) {
}

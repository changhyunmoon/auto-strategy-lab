package com.mch.strategy.viz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.dataset.Dataset;
import com.mch.strategy.dataset.DatasetColumn;
import com.mch.strategy.dataset.DatasetQueryService;
import com.mch.strategy.dataset.DatasetRowRepository;
import com.mch.strategy.dataset.DatasetRowRepository.Rows;
import com.mch.strategy.dataset.DatasetStatus;
import com.mch.strategy.training.JsonSafe;

import lombok.RequiredArgsConstructor;

/** 변수 탐색 화면: 데이터세트의 한 열을 읽어 {@link FeatureProfile} 로 요약한다. */
@Service
@RequiredArgsConstructor
public class FeatureProfileService {

    private final DatasetQueryService datasetQueryService;
    private final DatasetRowRepository rowRepository;

    @SuppressWarnings("unchecked")
    public Map<String, Object> profile(long datasetId, long assetId, String interval, String column, Integer horizon) {
        Dataset d = datasetQueryService.get(datasetId);
        if (d.getStatus() != DatasetStatus.READY) {
            throw ApiException.badRequest("완료된 데이터세트만 볼 수 있습니다.");
        }
        int minutes = Intervals.minutes(interval);
        List<DatasetColumn> columns = datasetQueryService.columns(datasetId);
        DatasetColumn target = columns.stream().filter(c -> c.getColumnName().equals(column)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("열이 없습니다: " + column));
        DatasetColumn close = columns.stream().filter(c -> c.getColumnName().equals("close")).findFirst()
                .orElseThrow(() -> ApiException.badRequest("종가 열이 없습니다."));
        int h = horizon != null && FeatureProfile.HORIZONS.contains(horizon) ? horizon : FeatureProfile.DEFAULT_HORIZON;

        Rows rows = rowRepository.load(datasetId, assetId, interval, null, null);
        Map<String, double[]> others = new LinkedHashMap<>();
        for (DatasetColumn c : columns) {
            if (!c.isBar() && c != target) others.put(c.getColumnName(), rows.column(c.getColumnIndex()));
        }
        Map<String, Object> out = FeatureProfile.compute(rows.time(), rows.column(target.getColumnIndex()),
                rows.column(close.getColumnIndex()), minutes, h, others);
        out.put("horizons", FeatureProfile.HORIZONS);
        out.put("intervalMinutes", minutes);
        return (Map<String, Object>) JsonSafe.clean(out);
    }
}

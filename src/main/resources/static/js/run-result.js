/*
 * 실험 결과(검증 · 최종 테스트) 그리기. 서버의 Evaluator 가 만든 JSON 을 그대로 받는다.
 * 순서: 요약 카드 → 실제·예측 비율 → 혼동행렬(칸을 누르면 사례) → 클래스별 표 → 기간별 성능
 *       → 예측별 실제 수익률 → (확률 모델) 정밀도-재현율 · 확률 보정 · 확률 기준표
 * 모든 섹션에 「읽는 법」을 붙인다. 값이 없으면(계산 불가) "–" 로 보여주고 0 과 구분한다.
 */
var ResultView = (function ($) {
  var COLORS = ['#2383e2', '#b9b9b5', '#e03e3e']; // 하락 · 중립 · 상승
  var LABELS = ['하락', '중립', '상승'];

  function help(lines) {
    var $d = $('<details class="help"><summary>ⓘ 읽는 법</summary><div class="help-body"></div></details>');
    lines.forEach(function (l) { $d.find('.help-body').append($('<p></p>').html(l)); });
    return $d;
  }

  /** 섹션 제목 · 카드 이름 → 용어 사전 키 (ⓘ 도움말) */
  var TERMS = {
    'Macro F1': 'macroF1', '균형 정확도': 'balancedAccuracy', '정확도': 'accuracy', 'Log loss': 'logLoss',
    '상승 정밀도 · 재현율': 'precision', '하락 정밀도 · 재현율': 'recall', '혼동행렬': 'confusion',
    '정밀도-재현율 곡선': 'prCurve', '확률 보정': 'calibration', '확률 기준별 신호': 'probThreshold'
  };

  function section(title, sub) {
    var $s = $('<div class="result-section"></div>');
    $s.append($('<h3></h3>').append($('<span></span>').attr('data-term', TERMS[title] || null).text(title))
      .append(sub ? $('<span class="count"></span>').text(sub) : ''));
    return $s;
  }

  function card(label, value, sub, cls, title) {
    var $c = $('<div class="card-stat"></div>').attr('title', title || '');
    $c.append($('<div class="label"></div>').append($('<span></span>').attr('data-term', TERMS[label] || null).text(label)));
    $c.append($('<div class="value"></div>').text(value));
    if (sub) $c.append($('<div class="sub"></div>').addClass(cls || '').text(sub));
    return $c;
  }

  function diff(v, base) {
    if (v == null || base == null) return null;
    var d = v - base;
    return { text: '최빈 클래스 대비 ' + (d >= 0 ? '+' : '') + d.toFixed(3), cls: d > 0 ? 'good' : 'bad' };
  }

  /**
   * @param $root  그릴 곳
   * @param result 결과 JSON
   * @param opts   { split: 'val' | 'test' }
   */
  function render($root, result, opts) {
    (($root.data('charts')) || []).forEach(function (c) { c.dispose(); });
    var charts = [];
    $root.data('charts', charts).empty();
    var assetKey = 'ALL';

    var $bar = $('<div class="toolbar"></div>').appendTo($root);
    var $chips = $('<span class="chip-group"></span>').appendTo($bar);
    var name = 'asset_' + opts.split;
    result.assetKeys.forEach(function (k, i) {
      $chips.append($('<label class="chip"></label>').append($('<input type="radio">').attr('name', name).val(k).prop('checked', i === 0))
        .append(document.createTextNode(k === 'ALL' ? '전체 자산' : k)));
    });
    $bar.append($('<span class="small muted" style="margin-left:8px"></span>').text(
      (opts.split === 'test' ? '최종 테스트' : '검증') + ' 구간 ' + App.formatNumber(result.rows) + '행' +
      (result.evaluatedAt ? ' · 평가 ' + App.toKst(result.evaluatedAt) + ' (' + result.runCount + '회째)' : '')));
    var $body = $('<div></div>').appendTo($root);

    function chart(el, option) {
      var c = echarts.init(el, 'clean');
      c.setOption(option);
      charts.push(c);
      return c;
    }

    function draw() {
      charts.splice(0).forEach(function (c) { c.dispose(); });
      $body.empty();
      var b = result.byAsset[assetKey];
      var s = b.summary;
      var base = assetKey === 'ALL' ? result.baseline : null;

      // ---------- 요약 카드 ----------
      var $sum = section('요약').appendTo($body);
      var $cards = $('<div class="cards"></div>').appendTo($sum);
      var up = s.perClass[2], down = s.perClass[0];
      var dF1 = diff(s.macroF1, base && base.macroF1), dAcc = diff(s.accuracy, base && base.accuracy);
      $cards.append(card('Macro F1', App.fixed(s.macroF1), dF1 && dF1.text, dF1 && dF1.cls));
      $cards.append(card('균형 정확도', App.pct(s.balancedAccuracy), '클래스별 재현율 평균', null));
      $cards.append(card('정확도', App.pct(s.accuracy), dAcc && dAcc.text.replace(/([+-]\d\.\d+)/, function (m) { return (Number(m) * 100).toFixed(1) + '%p'; }), dAcc && dAcc.cls));
      $cards.append(card('Log loss', App.fixed(s.logLoss), result.hasProba ? '낮을수록 확률이 정확' : '확률을 내지 않는 모델', null));
      $cards.append(card('상승 정밀도 · 재현율', App.pct(up.precision) + ' · ' + App.pct(up.recall), '상승 예측 ' + App.formatNumber(up.predicted) + '건 / 실제 ' + App.formatNumber(up.support) + '건'));
      $cards.append(card('하락 정밀도 · 재현율', App.pct(down.precision) + ' · ' + App.pct(down.recall), '하락 예측 ' + App.formatNumber(down.predicted) + '건 / 실제 ' + App.formatNumber(down.support) + '건'));
      $sum.append(help([
        '<b>무엇을 알 수 있나:</b> 이 구간에서 모델이 얼마나 맞혔는지 한눈에 봅니다. <b>최빈 클래스 대비</b>는 같은 구간에서 「항상 가장 흔한 정답(보통 중립)」으로 예측했을 때와의 차이입니다.',
        '<b>정밀도</b> = 모델이 「상승」이라고 했을 때 실제로 상승이었던 비율 (신호의 정확성). <b>재현율</b> = 실제 상승 중 모델이 찾아낸 비율.',
        '<b>주의:</b> 중립이 대부분이면 정확도는 높게 나옵니다. 정확도보다 Macro F1 · 상승/하락 정밀도를 먼저 보세요. 이 점수는 매매 수익이 아닙니다 (수수료 · 체결 미반영).'
      ]));

      // ---------- 실제 vs 예측 비율 ----------
      var $cnt = section('실제 정답 vs 예측 비율').appendTo($body);
      var cntEl = $('<div class="chart chart--sm"></div>').appendTo($cnt)[0];
      var n = s.n || 1;
      chart(cntEl, {
        grid: { left: 60, right: 20, top: 30, bottom: 30 }, legend: { top: 0 },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (ps) {
          return ps[0].axisValue + '<br>' + ps.map(function (p) {
            var raw = p.seriesIndex === 0 ? s.actualCounts[p.dataIndex] : s.predictedCounts[p.dataIndex];
            return p.marker + p.seriesName + ' ' + App.formatNumber(raw) + '건 (' + Number(p.value).toFixed(1) + '%)';
          }).join('<br>');
        } },
        xAxis: { type: 'category', data: LABELS },
        yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
        series: [
          { name: '실제', type: 'bar', data: s.actualCounts.map(function (v) { return v * 100 / n; }), itemStyle: { color: '#d3d3cf' } },
          { name: '예측', type: 'bar', data: s.predictedCounts.map(function (v, i) { return { value: v * 100 / n, itemStyle: { color: COLORS[i] } }; }) }
        ]
      });
      $cnt.append(help(['<b>질문:</b> 모델이 한 클래스(보통 중립)만 고르고 있지 않은가? 예측 막대가 실제와 크게 다르면 그 클래스를 과하게 또는 너무 적게 고른다는 뜻입니다.']));

      // ---------- 혼동행렬 ----------
      var $cm = section('혼동행렬', '칸을 누르면 그 경우의 사례를 봅니다').appendTo($body);
      var cmEl = $('<div class="chart chart--cm"></div>').appendTo($cm)[0];
      var cm = b.confusion, data = [], rowSum = cm.map(function (r) { return r[0] + r[1] + r[2]; });
      for (var a = 0; a < 3; a++) for (var p = 0; p < 3; p++) data.push([p, a, rowSum[a] ? cm[a][p] / rowSum[a] : 0, cm[a][p]]);
      var cmChart = chart(cmEl, {
        grid: { left: 80, right: 20, top: 30, bottom: 50 },
        tooltip: { formatter: function (pp) { return '실제 ' + LABELS[pp.value[1]] + ' → 예측 ' + LABELS[pp.value[0]] + '<br>' + App.formatNumber(pp.value[3]) + '건 · 실제 ' + LABELS[pp.value[1]] + ' 중 ' + App.pct(pp.value[2]); } },
        xAxis: { type: 'category', data: LABELS, name: '예측', nameLocation: 'middle', nameGap: 30, splitArea: { show: true } },
        yAxis: { type: 'category', data: LABELS, name: '실제', nameLocation: 'middle', nameGap: 50, inverse: true, splitArea: { show: true } },
        visualMap: { show: false, min: 0, max: 1, dimension: 2, inRange: { color: ['#f7f7f5', '#9cc7ee', '#2383e2'] } },
        series: [{ type: 'heatmap', data: data, label: { show: true, fontSize: 12, formatter: function (pp) { return App.formatNumber(pp.value[3]) + '\n' + App.pct(pp.value[2]); } } }]
      });
      var $samples = $('<div></div>').appendTo($cm);
      cmChart.on('click', function (pp) { showSamples($samples, pp.value[1], pp.value[0]); });
      $cm.append(help([
        '<b>읽는 법:</b> 행 = 실제 정답, 열 = 모델 예측. 대각선(왼쪽 위 → 오른쪽 아래)이 맞힌 경우입니다. % 는 같은 행(실제 정답) 안에서의 비율입니다.',
        '<b>질문:</b> 「실제 상승 → 예측 하락」처럼 방향이 정반대인 오류가 많은가? 매매에서는 이 오류가 가장 비쌉니다.'
      ]));

      // ---------- 클래스별 표 ----------
      var $cls = section('클래스별 성능').appendTo($body);
      var $t = $('<table class="db-table"><thead><tr><th>클래스</th><th class="num"><span data-term="precision">정밀도</span></th><th class="num"><span data-term="recall">재현율</span></th><th class="num"><span data-term="f1">F1</span></th><th class="num">실제 건수</th><th class="num">예측 건수</th></tr></thead><tbody></tbody></table>');
      s.perClass.forEach(function (c) {
        $t.find('tbody').append($('<tr></tr>').append($('<td></td>').text(c.label))
          .append($('<td class="num"></td>').text(App.pct(c.precision)).attr('title', c.precision == null ? '예측한 적이 없어 계산 불가' : ''))
          .append($('<td class="num"></td>').text(App.pct(c.recall)).attr('title', c.recall == null ? '실제 사례가 없어 계산 불가' : ''))
          .append($('<td class="num"></td>').text(App.fixed(c.f1)))
          .append($('<td class="num"></td>').text(App.formatNumber(c.support)))
          .append($('<td class="num"></td>').text(App.formatNumber(c.predicted))));
      });
      $('<div class="db-table-wrap"></div>').append($t).appendTo($cls);
      $cls.append(help(['「–」는 계산할 수 없다는 뜻입니다 (예: 한 번도 「상승」으로 예측하지 않으면 상승 정밀도는 계산 불가). 0% 와 다릅니다. 실제 건수가 적은 클래스의 점수는 크게 흔들립니다.']));

      // ---------- 기간별 성능 ----------
      var $per = section('기간별 성능').appendTo($body);
      var $pt = $('<span class="chip-group"></span>').appendTo($('<div class="toolbar"></div>').appendTo($per));
      [['monthly', '월별'], ['weekly', '주별']].forEach(function (x, i) {
        $pt.append($('<label class="chip"></label>').append($('<input type="radio">').attr('name', 'per_' + opts.split).val(x[0]).prop('checked', i === 0)).append(document.createTextNode(x[1])));
      });
      var perEl = $('<div class="chart chart--md"></div>').appendTo($per)[0];
      var perChart = chart(perEl, {});
      function drawPeriods(kind) {
        var rows = b[kind];
        perChart.setOption({
          grid: { left: 50, right: 60, top: 40, bottom: 50 }, legend: { top: 0 },
          tooltip: { trigger: 'axis' },
          xAxis: { type: 'category', data: rows.map(function (r) { return r.period; }) },
          yAxis: [{ type: 'value', min: 0, max: 1, name: '점수' }, { type: 'value', name: '행 수', splitLine: { show: false } }],
          dataZoom: rows.length > 30 ? [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 8 }] : [],
          series: [
            { name: '표본 수', type: 'bar', yAxisIndex: 1, data: rows.map(function (r) { return r.n; }), itemStyle: { color: '#ebebe9' }, barMaxWidth: 26 },
            { name: 'Macro F1', type: 'line', data: rows.map(function (r) { return r.macroF1; }), symbolSize: 5 },
            { name: '균형 정확도', type: 'line', data: rows.map(function (r) { return r.balancedAccuracy; }), symbolSize: 5 },
            { name: '정확도', type: 'line', data: rows.map(function (r) { return r.accuracy; }), symbolSize: 4, lineStyle: { type: 'dashed' } }
          ]
        }, true);
      }
      $per.on('change', 'input', function () { drawPeriods(this.value); });
      drawPeriods('monthly');
      $per.append(help([
        '<b>질문:</b> 특정 시기에만 성능이 좋은가? 점수가 기간마다 크게 오르내리면 그 모델은 시장 상황에 따라 흔들립니다.',
        '회색 막대는 그 기간의 행 수입니다. 행이 적은 기간(데이터 시작 · 끝, 누락이 많은 주)의 점수는 믿기 어렵습니다. 시각은 UTC 기준으로 묶었습니다.'
      ]));

      // ---------- 예측별 실제 수익률 ----------
      var $ret = section('예측별 실제 다음 봉 수익률', '% · 비용 미반영').appendTo($body);
      var retEl = $('<div class="chart chart--sm"></div>').appendTo($ret)[0];
      var rb = b.returnByPred;
      chart(retEl, {
        grid: { left: 60, right: 20, top: 20, bottom: 30 },
        tooltip: { trigger: 'item', formatter: function (pp) {
          var r = rb[pp.dataIndex];
          if (pp.seriesType === 'scatter') return '평균 ' + App.fixed(r.mean, 4) + '%';
          return '예측 ' + r.label + ' (' + App.formatNumber(r.n) + '건)<br>5% ' + App.fixed(r.p5, 4) + ' · 25% ' + App.fixed(r.p25, 4) +
            '<br>중앙값 ' + App.fixed(r.p50, 4) + '<br>75% ' + App.fixed(r.p75, 4) + ' · 95% ' + App.fixed(r.p95, 4);
        } },
        xAxis: { type: 'category', data: rb.map(function (r) { return '예측 ' + r.label + ' (' + App.formatNumber(r.n) + ')'; }) },
        yAxis: { type: 'value', name: '%', scale: true },
        series: [
          { type: 'boxplot', data: rb.map(function (r, i) { return { value: r.n ? [r.p5, r.p25, r.p50, r.p75, r.p95] : [], itemStyle: { borderColor: COLORS[i] } }; }) },
          { type: 'scatter', data: rb.map(function (r, i) { return r.mean == null ? null : [i, r.mean]; }), symbol: 'diamond', symbolSize: 9, itemStyle: { color: '#37352f' } }
        ]
      });
      $ret.append(help([
        '<b>질문:</b> 모델이 「상승」이라고 한 봉들이 실제로 더 많이 올랐는가? 상자는 25~75%, 수염은 5~95%, ◆ 는 평균입니다.',
        '「상승」 예측의 평균이 「하락」 예측보다 뚜렷이 높아야 예측이 방향을 구분한다는 뜻입니다. 수수료 · 호가 차이는 빠져 있어 그대로 수익이 되지 않습니다.'
      ]));

      if (result.hasProba && b.pr) {
        // ---------- 정밀도-재현율 ----------
        var $pr = section('정밀도-재현율 곡선', '상승 · 하락 각각').appendTo($body);
        var prEl = $('<div class="chart chart--md"></div>').appendTo($pr)[0];
        var series = [];
        [['up', '상승', COLORS[2]], ['down', '하락', COLORS[0]]].forEach(function (x) {
          var c = b.pr[x[0]];
          series.push({ name: x[1] + ' (AP ' + App.fixed(c.ap) + ')', type: 'line', showSymbol: false, data: c.points.map(function (pt) { return [pt[0], pt[1], pt[2]]; }), lineStyle: { color: x[2] }, itemStyle: { color: x[2] } });
          if (c.prevalence != null) series.push({ name: x[1] + ' 무작위 수준', type: 'line', showSymbol: false, data: [[0, c.prevalence], [1, c.prevalence]], lineStyle: { type: 'dashed', color: x[2], opacity: 0.5 }, itemStyle: { color: x[2] } });
        });
        chart(prEl, {
          grid: { left: 60, right: 30, top: 40, bottom: 50 }, legend: { top: 0 },
          tooltip: { trigger: 'item', formatter: function (pp) { return pp.seriesName + '<br>재현율 ' + App.pct(pp.value[0]) + ' · 정밀도 ' + App.pct(pp.value[1]) + (pp.value[2] != null ? '<br>확률 기준 ≥ ' + App.fixed(pp.value[2]) : ''); } },
          xAxis: { type: 'value', min: 0, max: 1, name: '재현율', nameLocation: 'middle', nameGap: 30 },
          yAxis: { type: 'value', min: 0, max: 1, name: '정밀도' },
          series: series
        });
        $pr.append(help([
          '<b>읽는 법:</b> 확률 기준을 높이면(왼쪽으로) 신호는 줄고 정밀도는 보통 올라갑니다. 곡선이 점선(그 클래스의 실제 비율 = 무작위로 찍을 때의 정밀도)보다 위에 있어야 의미가 있습니다.',
          '<b>AP</b>(평균 정밀도)는 곡선 아래 넓이에 가까운 값입니다. 무작위면 점선 높이와 비슷합니다.'
        ]));

        // ---------- 확률 보정 ----------
        var $cal = section('확률 보정', '예측 확률 vs 실제 비율 (같은 개수 10구간)').appendTo($body);
        var calEl = $('<div class="chart chart--md"></div>').appendTo($cal)[0];
        var calSeries = [{ name: '완벽한 보정', type: 'line', showSymbol: false, data: [[0, 0], [1, 1]], lineStyle: { type: 'dashed', color: '#b9b9b5' }, itemStyle: { color: '#b9b9b5' } }];
        var maxP = 0;
        [['up', '상승', COLORS[2]], ['down', '하락', COLORS[0]]].forEach(function (x) {
          var pts = b.calibration[x[0]].filter(function (c) { return c.predicted != null; });
          pts.forEach(function (c) { maxP = Math.max(maxP, c.predicted, c.actual); });
          calSeries.push({ name: x[1], type: 'line', data: pts.map(function (c) { return [c.predicted, c.actual, c.n]; }), symbolSize: 7, lineStyle: { color: x[2] }, itemStyle: { color: x[2] } });
        });
        var axisMax = Math.min(1, Math.ceil((maxP + 0.02) * 20) / 20);
        chart(calEl, {
          grid: { left: 60, right: 30, top: 40, bottom: 50 }, legend: { top: 0 },
          tooltip: { trigger: 'item', formatter: function (pp) { return pp.seriesName + '<br>예측 확률 평균 ' + App.pct(pp.value[0]) + '<br>실제 비율 ' + App.pct(pp.value[1]) + (pp.value[2] ? '<br>' + App.formatNumber(pp.value[2]) + '건' : ''); } },
          xAxis: { type: 'value', min: 0, max: axisMax, name: '예측 확률', nameLocation: 'middle', nameGap: 30 },
          yAxis: { type: 'value', min: 0, max: axisMax, name: '실제 비율' },
          series: calSeries
        });
        $cal.append(help([
          '<b>질문:</b> 모델이 「상승 확률 30%」라고 한 봉들이 실제로 약 30% 올랐는가? 점이 점선 위에 있으면 확률을 낮게, 아래에 있으면 높게(과신) 말한다는 뜻입니다.',
          '이 그래프는 진단용입니다. 보정 모델을 따로 학습하려면 학습 구간 안의 별도 자료가 필요합니다 (최종 테스트로 보정하지 않음).'
        ]));

        // ---------- 확률 기준표 ----------
        var $th = section('확률 기준별 신호', '나중에 매매 규칙의 기준을 정할 때 참고').appendTo($body);
        var $wrap = $('<div class="two-col"></div>').appendTo($th);
        [['up', '상승', '하락'], ['down', '하락', '상승']].forEach(function (x) {
          var $tbl = $('<table class="db-table db-table--compact"></table>');
          $tbl.append('<thead><tr><th>' + x[1] + ' 확률 ≥</th><th class="num">신호 수</th><th class="num">적중</th><th class="num">반대 방향</th><th class="num">평균 다음 봉 %</th></tr></thead>');
          var $tb = $('<tbody></tbody>').appendTo($tbl);
          b.thresholds[x[0]].forEach(function (r) {
            $tb.append($('<tr></tr>').append($('<td class="num"></td>').text(r.threshold.toFixed(2)))
              .append($('<td class="num"></td>').text(App.formatNumber(r.signals)))
              .append($('<td class="num"></td>').text(App.pct(r.precision)))
              .append($('<td class="num"></td>').text(App.pct(r.oppositeRate)))
              .append($('<td class="num"></td>').text(App.fixed(r.meanReturn, 4))));
          });
          $('<div></div>').append($('<div class="small muted" style="margin-bottom:4px"></div>').text(x[1] + ' 신호')).append($tbl).appendTo($wrap);
        });
        $th.append(help([
          '「상승 확률이 X 이상일 때만 행동」하면 신호가 몇 번 나오고, 그중 실제 상승(적중) · 실제 하락(반대 방향)이 몇 %였는지입니다.',
          '<b>주의:</b> 확률 기준은 정답 기준 %와 다른 설정입니다. 기준은 검증 구간에서 정하고 최종 테스트 전에 고정하세요. 최종 테스트 결과를 보고 기준을 바꾸면 그 테스트는 설정 선택에 쓴 것이 됩니다.'
        ]));
      }
    }

    function showSamples($box, actual, pred) {
      var rows = (result.samples[actual + '_' + pred] || []).filter(function (r) { return assetKey === 'ALL' || r.asset === assetKey; });
      $box.empty();
      $box.append($('<div class="small muted" style="margin:6px 0 4px"></div>').text('실제 ' + LABELS[actual] + ' → 예측 ' + LABELS[pred] + ' · 무작위 사례 최대 20개' + (assetKey === 'ALL' ? '' : ' 중 ' + assetKey)));
      if (!rows.length) { $box.append('<div class="small faint">사례가 없습니다.</div>'); return; }
      var $t = $('<table class="db-table db-table--compact"><thead><tr><th>시각 (KST)</th><th>자산</th><th class="num">다음 봉 수익률 %</th><th class="num">하락 · 중립 · 상승 확률</th></tr></thead><tbody></tbody></table>');
      rows.forEach(function (r) {
        $t.find('tbody').append($('<tr></tr>').append($('<td class="num"></td>').text(App.msToKst(r.time)))
          .append($('<td></td>').text(r.asset)).append($('<td class="num"></td>').text(App.fixed(r.forward, 4)))
          .append($('<td class="num"></td>').text(r.proba ? r.proba.map(function (v) { return App.pct(v, 0); }).join(' · ') : '–')));
      });
      $('<div class="db-table-wrap"></div>').append($t).appendTo($box);
    }

    $root.on('change', 'input[name="' + name + '"]', function () { assetKey = this.value; draw(); });
    draw();
    $(window).off('resize.' + opts.split).on('resize.' + opts.split, function () { charts.forEach(function (c) { c.resize(); }); });
  }

  /** 탭이 숨겨진 상태에서 그린 차트는 크기가 0 이므로, 탭을 열 때 다시 맞춘다 */
  function resizeAll($root) {
    (($root.data('charts')) || []).forEach(function (c) { c.resize(); });
  }

  return { render: render, resizeAll: resizeAll };
})(jQuery);

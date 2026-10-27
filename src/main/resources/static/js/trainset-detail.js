/* 학습 데이터 상세 화면: 구간 · 정답 분포 · 제외 사유 */
$(function () {
  var $page = $('#setPage');
  var id = $page.data('id');
  var SPLITS = [['train', '학습'], ['val', '검증'], ['test', '최종 테스트']];
  var CLASS_COLORS = ['#2383e2', '#b9b9b5', '#e03e3e']; // 하락 파랑 · 중립 회색 · 상승 빨강 (국내 관례)

  // ---------- 만드는 중이면 진행률 ----------
  if ($page.data('building') === true || $page.data('building') === 'true') {
    (function poll() {
      App.api('GET', '/api/jobs/' + $page.data('job')).done(function (job) {
        $('#progressBar').css('width', job.progress + '%');
        $('#progressText').text(App.jobText(job));
        if (job.status === 'RUNNING') setTimeout(poll, 1500); else location.reload();
      });
    })();
  }

  $('#deleteBtn').on('click', function () {
    var runs = Number($page.data('runs')) || 0;
    var extra = runs ? ' 이 데이터로 만든 실험 ' + runs + '개도 함께 지워집니다.' : '';
    if (!window.confirm('「' + $page.data('name') + '」을(를) 지울까요?' + extra)) return;
    App.api('DELETE', '/api/trainsets/' + id).done(function () { location.href = '/trainsets'; });
  });

  if (!STATS || !STATS.splits) return;
  var splits = STATS.splits;
  var PERIODS = { train: $page.data('train'), val: $page.data('val'), test: $page.data('test') };
  if (!STATS.alternatives) {
    $('#distChart').html('<div class="empty">예전 형식의 학습 데이터입니다. 「같은 설정으로 새로 만들기」로 다시 만들어 주세요.</div>');
    return;
  }
  var selected = STATS.selected;
  var tr0 = splits.train, va0 = splits.val;
  var hint = [];
  if (tr0.meanThresholdPct != null) hint.push('학습 구간 평균 기준 ±' + App.fixed(tr0.meanThresholdPct, 3) + '% · 검증 ±' + App.fixed(va0.meanThresholdPct, 3) + '%');
  if (tr0.ambiguous || va0.ambiguous) hint.push('목표 · 손절 동시 도달(중립 처리) 학습 ' + App.formatNumber(tr0.ambiguous) + '건 · 검증 ' + App.formatNumber(va0.ambiguous) + '건');
  $('#labelStatsHint').text(hint.join(' · '));

  // 정답이 중립에 크게 쏠리면 모델이 중립만 고르기 쉽다 → 경고 + 덜 쏠리는 기준 추천
  var cur = (tr0.classes && tr0.classes.ALL && tr0.classes.ALL[selected]) || null;
  if (cur) {
    var n = cur[0] + cur[1] + cur[2], neutral = n ? cur[1] / n : 0;
    if (neutral > 0.7) {
      var best = null;
      Object.keys(tr0.classes.ALL).forEach(function (k) {
        var c = tr0.classes.ALL[k], m = c[0] + c[1] + c[2], share = m ? c[1] / m : 1;
        if (best == null || Math.abs(share - 0.55) < Math.abs(best.share - 0.55)) best = { k: k, share: share };
      });
      $('#labelStatsHint').before($('<div class="callout callout-yellow small" style="margin:8px 0"></div>').append('<div class="callout-icon">⚠️</div>')
        .append($('<div class="callout-body"></div>').append($('<b></b>').text('정답의 ' + Math.round(neutral * 100) + '% 가 중립입니다. '))
          .append(document.createTextNode('모델이 「항상 중립」으로 굳어 거래가 거의 없을 수 있습니다.'
            + (best && best.k !== selected ? ' 아래 「다른 기준이었다면」 표의 「' + best.k + '」(중립 약 ' + Math.round(best.share * 100) + '%)처럼 기준을 낮춰 다시 만드는 것을 권합니다.' : '')))));
    }
  }

  // ---------- 구간 ----------
  var total = SPLITS.reduce(function (s, p) { return s + (splits[p[0]].rows || 0); }, 0);
  var $tl = $('#timeline');
  SPLITS.forEach(function (p) {
    var n = splits[p[0]].rows || 0;
    $tl.append($('<div class="seg"></div>').addClass('seg-' + p[0]).css('flex', String(Math.max(n, total * 0.02)))
      .text(p[1] + ' ' + App.formatNumber(n)));
  });
  var $tb = $('#splitTable tbody');
  SPLITS.forEach(function (p) {
    var s = splits[p[0]];
    var byAsset = Object.keys(s.byAsset).map(function (a) { return a + ' ' + App.formatNumber(s.byAsset[a]); }).join(' · ');
    $tb.append($('<tr></tr>')
      .append($('<td></td>').append($('<span class="tag"></span>').addClass(p[0] === 'train' ? 'tag-blue' : p[0] === 'val' ? 'tag-yellow' : 'tag-red').text(p[1])))
      .append($('<td class="num"></td>').text(PERIODS[p[0]]))
      .append($('<td class="num muted"></td>').text(s.first ? App.toKst(s.first) + ' ~ ' + App.toKst(s.last) : '-'))
      .append($('<td class="num"></td>').text(App.formatNumber(s.rows)))
      .append($('<td class="muted small"></td>').text(byAsset)));
  });

  // ---------- 정답 분포 (선택한 기준 %) ----------
  var assetKeys = ['ALL'].concat(STATS.assets);
  assetKeys.forEach(function (a, i) {
    $('#distAsset').append($('<label class="chip"></label>')
      .append($('<input type="radio" name="distAsset">').val(a).prop('checked', i === 0))
      .append(document.createTextNode(a === 'ALL' ? '전체' : a)));
  });
  var chart = echarts.init(document.getElementById('distChart'), 'clean');
  function drawDist() {
    var a = $('input[name="distAsset"]:checked').val();
    var rows = ['train', 'val'].map(function (k) { return splits[k].classes[a][selected]; });
    var labels = ['하락', '중립', '상승'];
    chart.setOption({
      grid: { left: 70, right: 30, top: 40, bottom: 30 },
      legend: { top: 0 },
      tooltip: {
        trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (ps) {
          return ps[0].axisValue + '<br>' + ps.map(function (p) {
            var r = rows[p.dataIndex], sum = r[0] + r[1] + r[2];
            return p.marker + p.seriesName + ' ' + App.formatNumber(r[p.seriesIndex]) + '행 (' + App.pct(r[p.seriesIndex] / sum) + ')';
          }).join('<br>');
        }
      },
      xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      yAxis: { type: 'category', data: ['학습', '검증'], inverse: true },
      series: labels.map(function (l, c) {
        return {
          name: l, type: 'bar', stack: 'all', itemStyle: { color: CLASS_COLORS[c] }, barWidth: 26,
          label: { show: true, formatter: function (p) { return p.value >= 4 ? p.value.toFixed(1) + '%' : ''; }, color: c === 1 ? '#37352f' : '#fff', fontSize: 11 },
          data: rows.map(function (r) { var s = r[0] + r[1] + r[2]; return s ? r[c] * 100 / s : 0; })
        };
      })
    }, true);
  }
  $(document).on('change', 'input[name="distAsset"]', drawDist);
  drawDist();
  $(window).on('resize', function () { chart.resize(); });

  // ---------- 다른 기준 % 표 ----------
  var $th = $('#thresholdTable');
  $th.append('<thead><tr><th>기준</th><th class="num">학습 상승</th><th class="num">학습 중립</th><th class="num">학습 하락</th>' +
    '<th class="num">검증 상승</th><th class="num">검증 중립</th><th class="num">검증 하락</th><th class="num">검증 상승·하락 건수</th></tr></thead>');
  var $body = $('<tbody></tbody>').appendTo($th);
  STATS.alternatives.forEach(function (t) {
    var tr = splits.train.classes.ALL[t], va = splits.val.classes.ALL[t];
    var isSel = t === selected;
    var st = tr[0] + tr[1] + tr[2], sv = va[0] + va[1] + va[2];
    var $tr = $('<tr></tr>').toggleClass('fw-semibold', isSel);
    $tr.append($('<td></td>').text(t + (isSel ? '  ← 선택' : '')));
    [tr[2], tr[1], tr[0]].forEach(function (v) { $tr.append($('<td class="num"></td>').text(App.pct(v / st))); });
    [va[2], va[1], va[0]].forEach(function (v) { $tr.append($('<td class="num"></td>').text(App.pct(v / sv))); });
    $tr.append($('<td class="num"></td>').text(App.formatNumber(va[0] + va[2])));
    $body.append($tr);
  });

  // ---------- 제외 사유 ----------
  var REASONS = {
    outside: ['구간 밖', '학습 · 검증 · 테스트 어느 날짜에도 속하지 않음 (구간 사이 빈 날 포함)'],
    noNextBar: ['예측 기간 봉 없음', '데이터 끝이거나, 예측 기간(N봉) 안에 빈 봉이 있어 정답을 만들 수 없음'],
    crossesBoundary: ['구간 경계 넘음', '정답에 쓰는 N봉이 다음 구간에 걸쳐 정보가 새어 들 수 있음'],
    noVolatility: ['변동성 계산 불가', '변동성 기준인데 과거 봉이 부족함 (데이터 앞부분)'],
    missingPrice: ['가격 없음', '종가가 비어 있음']
  };
  var $ex = $('#excludedTable tbody');
  Object.keys(STATS.excluded).forEach(function (k) {
    var r = REASONS[k] || [k, ''];
    $ex.append($('<tr></tr>').append($('<td></td>').text(r[0])).append($('<td class="num"></td>').text(App.formatNumber(STATS.excluded[k])))
      .append($('<td class="muted small"></td>').text(r[1])));
  });
  Object.keys(STATS.extraMissing || {}).forEach(function (iv) {
    $ex.append($('<tr></tr>').append($('<td></td>').text(iv.replace('m', '분') + ' 지표 비어 있음'))
      .append($('<td class="num"></td>').text(App.formatNumber(STATS.extraMissing[iv])))
      .append($('<td class="muted small"></td>').text('행은 남기고 그 간격 지표만 비움 (바로 앞 완성 봉이 빈 구간이거나 데이터 시작 부분)')));
  });
});

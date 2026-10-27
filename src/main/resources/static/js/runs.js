/* 실험 결과 목록: 학습 데이터별 필터 · 검증 점수 비교 차트 · 표 */
$(function () {
  var all = [];
  var timer = null;
  // 기본 정렬: 생성일 최신순 (실험 번호는 만든 순서대로 커지므로 번호로 정렬한다)
  var sortKey = 'id', sortDesc = true;
  var chart = echarts.init(document.getElementById('compareChart'), 'clean');
  var returnChart = echarts.init(document.getElementById('returnChart'), 'clean');

  function group(r) {
    return r.status === 'READY' ? 'READY' : r.status === 'FAILED' ? 'FAILED' : 'RUNNING';
  }

  function statusCell(r) {
    if (r.status === 'RUNNING') return App.tag((r.step || '학습 중') + ' ' + r.progress + '%', 'blue');
    if (r.status === 'PENDING') return App.tag('대기', '');
    if (r.status === 'FAILED') return App.tag('실패', 'red').attr('title', r.message);
    if (r.testStatus === 'RUNNING') return App.tag('테스트 중 ' + r.testProgress + '%', 'blue');
    return App.tag('완료', 'green');
  }

  function testCell(r) {
    if (r.testStatus === 'DONE') {
      return $('<span></span>').text(App.fixed(r.testMacroF1)).attr('title', '최종 테스트 Macro F1 · ' + r.testRunCount + '회 실행 · ' + r.testEvaluatedAt)
        .append($('<span class="faint small"></span>').text(r.testRunCount > 1 ? ' (' + r.testRunCount + '회)' : ''));
    }
    if (r.testStatus === 'FAILED') return $('<span class="small" style="color:var(--tag-red-fg)">실패</span>');
    return $('<span class="faint small">미실행</span>');
  }

  function seconds(s) {
    if (s == null) return '';
    s = Math.round(s);
    return s >= 60 ? Math.floor(s / 60) + '분 ' + (s % 60) + '초' : s + '초';
  }

  function filtered() {
    var s = $('input[name="status"]:checked').val();
    var list = all.filter(function (r) { return s === 'ALL' || group(r) === s; });
    return list.sort(function (a, b) {
      var x = a[sortKey], y = b[sortKey];
      if (x == null && y == null) return b.id - a.id;
      if (x == null) return 1;
      if (y == null) return -1;
      return sortDesc ? y - x : x - y;
    });
  }

  function render() {
    var counts = { ALL: all.length, READY: 0, RUNNING: 0, FAILED: 0 };
    all.forEach(function (r) { counts[group(r)]++; });
    Object.keys(counts).forEach(function (k) { $('[data-count="' + k + '"]').text(counts[k]); });

    var list = filtered();
    var $tb = $('#runTable tbody').empty();
    list.forEach(function (r) {
      var $tr = $('<tr class="clickable"></tr>').attr('data-id', r.id);
      $tr.append($('<td></td>').append(r.busy
        ? $('<span class="faint small" title="실행 중 · 대기 중인 실험은 끝난 뒤에 지울 수 있습니다">–</span>')
        : $('<input type="checkbox" class="form-check-input run-check">').attr('aria-label', r.name + ' 선택').prop('checked', !!picked[r.id])));
      // 실험 칸: 이름 + 모델 · 학습 데이터 · 검증 방식 (표 폭을 줄이려고 한 칸에 모은다)
      var $sub = $('<div class="run-sub"></div>')
        .append($('<span class="tag"></span>').text(r.modelLabel).attr('title', r.trainUnit + ' · ' + r.balance))
        .append(document.createTextNode(' ' + r.trainingSetName + ' · ' + (r.folds > 1 ? r.validation + ' ' + r.folds + '폴드' : r.validation)));
      $tr.append($('<td class="run-name"></td>').append($('<span class="row-title"></span>').text(r.name)).append($sub));
      $tr.append($('<td></td>').append(statusCell(r)));
      var gap = r.trainMacroF1 != null && r.valMacroF1 != null ? r.trainMacroF1 - r.valMacroF1 : null;
      var $f1 = $('<td class="num"></td>').text(App.fixed(r.valMacroF1))
        .attr('title', 'Log loss ' + App.fixed(r.valLogLoss) + ' · 학습 − 검증 차이 ' + (gap == null ? '–' : (gap >= 0 ? '+' : '') + gap.toFixed(3))
          + (gap != null && gap > 0.1 ? ' (과적합 의심)' : '') + ' · 학습 ' + seconds(r.trainSeconds));
      if (r.valMacroF1 != null && r.valBaselineMacroF1 != null) {
        var d = r.valMacroF1 - r.valBaselineMacroF1;
        $f1.append($('<span class="small"></span>').addClass(d > 0 ? 'diff-up' : 'diff-down').text(' (' + (d >= 0 ? '+' : '') + d.toFixed(3) + ')'));
      }
      $tr.append($f1);
      $tr.append($('<td class="num"></td>').text(r.valFoldF1Min == null ? '–' : App.fixed(r.valFoldF1Min))
        .attr('title', r.valFoldF1Mean == null ? '워크포워드에서만 계산' : '폴드 평균 ' + App.fixed(r.valFoldF1Mean) + ' ± ' + App.fixed(r.valFoldF1Std)));
      $tr.append($('<td class="num"></td>').text(App.pct(r.valBalancedAccuracy)));
      $tr.append($('<td class="num"></td>').text(r.valNetReturn == null ? '–' : (r.valNetReturn >= 0 ? '+' : '') + r.valNetReturn.toFixed(2) + '%')
        .toggleClass('diff-up', r.valNetReturn > 0).toggleClass('diff-down', r.valNetReturn < 0)
        .append(r.valTrades == null ? '' : $('<div class="faint small"></div>').text('거래 ' + App.formatNumber(r.valTrades) + '번')));
      $tr.append($('<td class="num"></td>').text(r.valMaxDrawdown == null ? '–' : r.valMaxDrawdown.toFixed(2) + '%'));
      $tr.append($('<td class="num"></td>').append(testCell(r)));
      var created = String(r.createdAt || '').split(' ');
      $tr.append($('<td class="num small"></td>').text(created[0] || '').append($('<div class="faint"></div>').text(created[1] || '')));
      var $act = $('<td style="white-space:nowrap"></td>');
      if ((r.status === 'RUNNING' || r.status === 'PENDING') && r.jobId) $act.append(App.cancelButton(r.jobId));
      else if (r.testStatus === 'RUNNING' && r.testJobId) $act.append(App.cancelButton(r.testJobId, '■ 테스트 중지'));
      if (!r.busy) $act.append('<button class="btn btn-danger-ghost btn-sm del">삭제</button>');
      $tr.append($act);
      $tb.append($tr);
    });
    syncPicked();
    $('#emptyBox').toggle(all.length === 0);
    $('#runTable').closest('.db-table-wrap').toggle(all.length > 0);
    // 정렬 표시는 별도 칸에만 쓴다 (제목 글자를 다시 쓰면 ⓘ 도움말 버튼이 지워진다)
    $('.sortable').each(function () {
      var $h = $(this), k = $h.data('sort');
      if (!$h.data('sortInit')) {
        $h.contents().filter(function () { return this.nodeType === 3; }).each(function () { this.nodeValue = this.nodeValue.replace(/ [▾▴]\s*$/, ''); });
        $h.append('<span class="sort-mark"></span>').css('cursor', 'pointer').data('sortInit', true);
      }
      $h.find('.sort-mark').text(k === sortKey ? (sortDesc ? ' ▾' : ' ▴') : '');
    });
    drawCompare();
  }

  function drawCompare() {
    var setId = $('#setFilter').val();
    var done = all.filter(function (r) { return r.status === 'READY' && r.valMacroF1 != null; })
      .sort(function (a, b) { return a.valMacroF1 - b.valMacroF1; });
    $('#pickSetHint').toggle(!setId && all.length > 0);
    $('#compareBox').toggle(!!setId && done.length > 0);
    if (!setId || !done.length) return;
    $('#compareCount').text('완료 ' + done.length + '개');
    var baseline = done.map(function (r) { return r.valBaselineMacroF1; }).filter(function (v) { return v != null; })[0];
    var names = done.map(function (r) { return '#' + r.id + ' ' + r.modelLabel; });
    $('#compareChart').css('height', Math.max(240, 60 + done.length * 46) + 'px');
    chart.resize();
    chart.setOption({
      grid: { left: 190, right: 60, top: 36, bottom: 30 },
      legend: { top: 0 },
      tooltip: {
        trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (ps) {
          var r = done[ps[0].dataIndex];
          return '<b>' + $('<div>').text(r.name).html() + '</b><br>' + ps.map(function (p) { return p.marker + p.seriesName + ' ' + Number(p.value).toFixed(3); }).join('<br>') +
            (r.testStatus === 'DONE' ? '<br>최종 테스트 Macro F1 ' + App.fixed(r.testMacroF1) : '');
        }
      },
      xAxis: { type: 'value', min: 0 },
      yAxis: { type: 'category', data: names, axisLabel: { width: 170, overflow: 'truncate' } },
      series: [
        {
          name: '검증 Macro F1', type: 'bar', barGap: '10%', barWidth: 14, data: done.map(function (r) { return r.valMacroF1; }),
          label: { show: true, position: 'right', fontSize: 11, formatter: function (p) { return Number(p.value).toFixed(3); } },
          markLine: baseline == null ? undefined : {
            symbol: 'none', lineStyle: { type: 'dashed', color: '#787774' },
            label: { formatter: '최빈 클래스 ' + baseline.toFixed(3), color: '#787774', fontSize: 11 },
            data: [{ xAxis: baseline }]
          }
        },
        { name: '균형 정확도', type: 'bar', barWidth: 14, data: done.map(function (r) { return r.valBalancedAccuracy; }), itemStyle: { color: '#d9730d' } },
        { name: '폴드 최저 Macro F1', type: 'scatter', symbol: 'diamond', symbolSize: 10, data: done.map(function (r) { return r.valFoldF1Min; }), itemStyle: { color: '#37352f' } }
      ]
    }, true);

    // 검증 순수익 (기본 규칙)
    var withTrade = done.filter(function (r) { return r.valNetReturn != null; });
    $('#returnChart').css('height', Math.max(200, 50 + withTrade.length * 30) + 'px');
    returnChart.resize();
    returnChart.setOption({
      grid: { left: 190, right: 70, top: 10, bottom: 30 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (ps) {
        var r = withTrade[ps[0].dataIndex];
        return '<b>' + $('<div>').text(r.name).html() + '</b><br>순수익 ' + r.valNetReturn.toFixed(2) + '% · 최대 낙폭 ' + App.fixed(r.valMaxDrawdown, 2) + '% · 거래 ' + r.valTrades + '번' +
          (r.testNetReturn != null ? '<br>최종 테스트 순수익 ' + r.testNetReturn.toFixed(2) + '%' : '');
      } },
      xAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
      yAxis: { type: 'category', data: withTrade.map(function (r) { return '#' + r.id + ' ' + r.modelLabel; }), axisLabel: { width: 170, overflow: 'truncate' } },
      series: [{ type: 'bar', barWidth: 14, data: withTrade.map(function (r) { return { value: r.valNetReturn, itemStyle: { color: r.valNetReturn >= 0 ? '#e03e3e' : '#2383e2' } }; }),
        label: { show: true, position: 'right', fontSize: 11, formatter: function (p) { return Number(p.value).toFixed(2) + '%'; } } }]
    }, true);
  }

  function load() {
    var setId = $('#setFilter').val();
    App.api('GET', '/api/runs' + (setId ? '?trainingSetId=' + setId : '')).done(function (list) {
      all = list;
      render();
      clearTimeout(timer);
      $('#cancelAll').toggle(list.some(function (r) { return r.status === 'RUNNING' || r.status === 'PENDING'; }));
      if (list.some(function (r) { return r.busy; })) timer = setTimeout(load, 2000);
    });
  }

  $('#setFilter').on('change', function () {
    var v = this.value;
    history.replaceState(null, '', v ? '/runs?trainingSetId=' + v : '/runs');
    $('#newRunLink').attr('href', v ? '/training?trainingSetId=' + v : '/training');
    load();
  });
  $('input[name="status"]').on('change', render);
  $('#runTable').on('click', '.sortable', function () {
    var k = $(this).data('sort');
    if (k === sortKey) sortDesc = !sortDesc; else { sortKey = k; sortDesc = k !== 'valLogLoss'; }
    render();
  });
  // ---------- 여러 개 골라 지우기 ----------
  var picked = {};
  function syncPicked() {
    // 목록에서 사라졌거나 실행 중이 된 실험은 선택에서 뺀다
    var deletable = {};
    all.forEach(function (r) { if (!r.busy) deletable[r.id] = true; });
    Object.keys(picked).forEach(function (id) { if (!deletable[id]) delete picked[id]; });
    var n = Object.keys(picked).length;
    $('#bulkDelete').prop('disabled', n === 0).text(n ? '선택한 실험 삭제 (' + n + ')' : '선택한 실험 삭제');
    var $checks = $('#runTable .run-check');
    $('#checkAllRuns').prop('checked', $checks.length > 0 && $checks.filter(':checked').length === $checks.length);
  }
  $('#runTable').on('change', '.run-check', function () {
    var id = $(this).closest('tr').data('id');
    if (this.checked) picked[id] = true; else delete picked[id];
    syncPicked();
  });
  $('#checkAllRuns').on('change', function () {
    var on = this.checked;
    $('#runTable .run-check').each(function () {
      var id = $(this).closest('tr').data('id');
      this.checked = on;
      if (on) picked[id] = true; else delete picked[id];
    });
    syncPicked();
  });
  $('#bulkDelete').on('click', function () {
    var ids = Object.keys(picked).map(Number);
    if (!ids.length) return;
    var names = all.filter(function (r) { return picked[r.id]; }).map(function (r) { return '· #' + r.id + ' ' + r.name; });
    if (!window.confirm('실험 ' + ids.length + '개를 지울까요? 저장된 모델 · 예측 · 결과 파일도 함께 지워지고 되돌릴 수 없습니다.\n\n'
      + names.slice(0, 15).join('\n') + (names.length > 15 ? '\n… 외 ' + (names.length - 15) + '개' : ''))) return;
    $('#bulkDelete').prop('disabled', true).text('지우는 중…');
    App.api('POST', '/api/runs/delete', { ids: ids }).done(function (res) {
      picked = {};
      App.toast(res.deleted.length + '개를 삭제했습니다.' + (res.skipped.length ? ' (실행 중이라 건너뜀 ' + res.skipped.length + '개)' : ''));
      load();
    }).always(syncPicked);
  });

  $('#runTable').on('click', 'tr.clickable', function (e) {
    if ($(e.target).closest('button, input').length) return;
    location.href = '/runs/' + $(this).data('id');
  });
  $('#runTable').on('click', '.del', function () {
    var id = $(this).closest('tr').data('id');
    var r = all.filter(function (x) { return x.id === id; })[0];
    if (!window.confirm('「' + r.name + '」 실험을 지울까요? 저장된 모델 · 예측 · 결과 파일도 지워집니다.')) return;
    App.api('DELETE', '/api/runs/' + id).done(function () { App.toast('삭제했습니다.'); load(); });
  });
  $(window).on('resize', function () { chart.resize(); returnChart.resize(); });

  if ($('#setFilter').val()) $('#newRunLink').attr('href', '/training?trainingSetId=' + $('#setFilter').val());
  load();
  // ---------- 학습 중 · 대기 중인 실험 모두 중지 ----------
  $('#cancelAll').on('click', function () {
    var jobs = all.filter(function (r) { return (r.status === 'RUNNING' || r.status === 'PENDING') && r.jobId; })
      .map(function (r) { return r.jobId; });
    if (!jobs.length || !window.confirm('학습 중 · 대기 중인 실험 ' + jobs.length + '개를 모두 중지할까요?\n대기 중인 실험은 시작하지 않고 끝납니다.')) return;
    var $b = $(this).prop('disabled', true);
    $.when.apply($, jobs.map(function (j) { return App.api('POST', '/api/jobs/' + j + '/cancel'); }))
      .always(function () { $b.prop('disabled', false); App.toast('중지를 요청했습니다. 몇 초 안에 멈춥니다.'); load(); });
  });
  $(document).on('job:cancel-requested', function () { load(); });
});

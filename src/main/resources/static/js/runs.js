/* 실험 결과 목록: 학습 데이터별 필터 · 검증 점수 비교 차트 · 표 */
$(function () {
  var all = [];
  var timer = null;
  var sortKey = 'valMacroF1', sortDesc = true;
  var chart = echarts.init(document.getElementById('compareChart'), 'clean');

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
      $tr.append($('<td></td>').append($('<span class="row-title"></span>').text(r.name)));
      $tr.append($('<td></td>').append($('<span class="tag"></span>').text(r.modelLabel)).attr('title', r.trainUnit + ' · ' + r.balance));
      $tr.append($('<td class="muted small"></td>').text(r.trainingSetName));
      $tr.append($('<td></td>').append(statusCell(r)));
      var $f1 = $('<td class="num"></td>').text(App.fixed(r.valMacroF1));
      if (r.valMacroF1 != null && r.valBaselineMacroF1 != null) {
        var d = r.valMacroF1 - r.valBaselineMacroF1;
        $f1.append($('<span class="small"></span>').addClass(d > 0 ? 'diff-up' : 'diff-down').text(' (' + (d >= 0 ? '+' : '') + d.toFixed(3) + ')'));
      }
      $tr.append($f1);
      $tr.append($('<td class="num"></td>').text(App.pct(r.valBalancedAccuracy)));
      $tr.append($('<td class="num"></td>').text(App.pct(r.valAccuracy)));
      $tr.append($('<td class="num"></td>').text(App.fixed(r.valLogLoss)));
      var gap = r.trainMacroF1 != null && r.valMacroF1 != null ? r.trainMacroF1 - r.valMacroF1 : null;
      $tr.append($('<td class="num"></td>').text(gap == null ? '–' : (gap >= 0 ? '+' : '') + gap.toFixed(3)).toggleClass('diff-down', gap != null && gap > 0.1));
      $tr.append($('<td class="num"></td>').append(testCell(r)));
      $tr.append($('<td class="num muted"></td>').text(seconds(r.trainSeconds)));
      $tr.append($('<td class="muted"></td>').text(r.createdAt));
      $tr.append($('<td></td>').append(r.busy ? '' : '<button class="btn btn-danger-ghost btn-sm del">삭제</button>'));
      $tb.append($tr);
    });
    $('#emptyBox').toggle(all.length === 0);
    $('#runTable').closest('.db-table-wrap').toggle(all.length > 0);
    $('.sortable').each(function () {
      var k = $(this).data('sort'), base = $(this).text().replace(/ [▾▴]$/, '');
      $(this).text(base + (k === sortKey ? (sortDesc ? ' ▾' : ' ▴') : '')).css('cursor', 'pointer');
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
        { name: '균형 정확도', type: 'bar', barWidth: 14, data: done.map(function (r) { return r.valBalancedAccuracy; }), itemStyle: { color: '#d9730d' } }
      ]
    }, true);
  }

  function load() {
    var setId = $('#setFilter').val();
    App.api('GET', '/api/runs' + (setId ? '?trainingSetId=' + setId : '')).done(function (list) {
      all = list;
      render();
      clearTimeout(timer);
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
  $('#runTable').on('click', 'tr.clickable', function (e) {
    if ($(e.target).closest('button').length) return;
    location.href = '/runs/' + $(this).data('id');
  });
  $('#runTable').on('click', '.del', function () {
    var id = $(this).closest('tr').data('id');
    var r = all.filter(function (x) { return x.id === id; })[0];
    if (!window.confirm('「' + r.name + '」 실험을 지울까요? 저장된 모델 · 예측 · 결과 파일도 지워집니다.')) return;
    App.api('DELETE', '/api/runs/' + id).done(function () { App.toast('삭제했습니다.'); load(); });
  });
  $(window).on('resize', function () { chart.resize(); });

  if ($('#setFilter').val()) $('#newRunLink').attr('href', '/training?trainingSetId=' + $('#setFilter').val());
  load();
});

/* 원본 데이터 받기 화면: 종목 × 거래소의 1분봉을 받아 DB 에 저장한다 */
$(function () {
  var DAY = 86400000;
  var $form = $('#rawForm');
  var maxDate = String($form.data('max-date'));
  var warmupDays = Number($form.data('warmup-days')) || 0;
  var coverage = [];
  var running = false;

  function selected(name) {
    return $('input[name="' + name + '"]:checked').map(function () { return this.value; }).get();
  }

  function utcDate(ms) { return new Date(ms).toISOString().slice(0, 10); }
  function rangeText(from, to) { return utcDate(from) + ' ~ ' + utcDate(to - 1); }

  /** 받을 구간 [from, to) (epoch ms, 워밍업 포함) */
  function requestedRange() {
    var from = Date.parse($('#dateFrom').val() + 'T00:00:00Z');
    if ($('#includeWarmup').prop('checked')) from -= warmupDays * DAY;
    return { from: from, to: Date.parse($('#dateTo').val() + 'T00:00:00Z') + DAY };
  }

  /** 받아 둔 구간들로 [from, to) 중 빠진 시간 (분) */
  function missingMinutes(ranges, from, to) {
    var missing = 0, cursor = from;
    (ranges || []).forEach(function (r) {
      var a = Date.parse(r.from), b = Date.parse(r.to);
      if (cursor >= to || b <= cursor || a >= to) return;
      if (a > cursor) missing += a - cursor;
      cursor = Math.max(cursor, b);
    });
    if (cursor < to) missing += to - cursor;
    return Math.round(missing / 60000);
  }

  // ---------- 받아 둔 원본 · 이력 ----------
  function loadCoverage() {
    App.api('GET', '/api/raw/coverage').done(function (list) {
      coverage = list;
      var $tb = $('#coverageTable tbody').empty();
      list.forEach(function (m) {
        var ranges = (m.ranges || []).map(function (r) { return rangeText(Date.parse(r.from), Date.parse(r.to)); });
        $('<tr>')
          .append($('<td>').append($('<b>').text(m.assetCode)))
          .append($('<td>').text(m.exchangeLabel + (m.enabled ? '' : ' (꺼짐)')))
          .append($('<td class="mono">').text(m.symbol))
          .append($('<td>').html(ranges.length ? ranges.map(function (t) { return $('<div>').text(t).html(); }).join('<br>')
            : '<span class="faint">아직 없음</span>'))
          .append($('<td class="num">').text(App.formatNumber(m.count)))
          .appendTo($tb);
      });
      if (!list.length) $tb.html('<tr><td colspan="5" class="muted">마켓이 없습니다.</td></tr>');
      updateSummary();
    });
  }

  function loadLogs() {
    App.api('GET', '/api/raw/logs').done(function (list) {
      var $tb = $('#logTable tbody').empty();
      var colors = { SUCCESS: 'green', FAILED: 'red', RUNNING: 'blue' };
      var labels = { SUCCESS: '완료', FAILED: '실패', RUNNING: '받는 중' };
      list.forEach(function (l) {
        var $status = $('<td>').append(App.tag(labels[l.status] || l.status, colors[l.status]));
        if (l.error) $status.append($('<div class="small muted">').text(l.error));
        $('<tr>')
          .append($('<td>').text(App.toKst(l.requestedAt)))
          .append($('<td>').text(l.market))
          .append($('<td>').text(rangeText(Date.parse(l.from), Date.parse(l.to))))
          .append($('<td class="num">').text(App.formatNumber(l.rowCount)))
          .append($status)
          .appendTo($tb);
      });
      if (!list.length) $tb.html('<tr><td colspan="5" class="muted">아직 받은 적이 없습니다.</td></tr>');
    });
  }

  // ---------- 요약 ----------
  function updateSummary() {
    var assets = selected('asset'), exchanges = selected('exchange'), r = requestedRange();
    var markets = coverage.filter(function (m) {
      return assets.indexOf(String(m.assetId)) >= 0 && exchanges.indexOf(m.exchange) >= 0;
    });
    var upbitMinutes = 0, binanceMinutes = 0;
    markets.forEach(function (m) {
      var miss = missingMinutes(m.ranges, r.from, r.to);
      if (m.exchange === 'UPBIT') upbitMinutes += miss; else binanceMinutes += miss;
    });
    // 업비트: 200개씩 초당 약 8번 · 바이낸스: 하루 파일 하나에 약 3초
    var minutes = Math.round(upbitMinutes / 200 / 8 / 60 + binanceMinutes / 1440 * 3 / 60);
    $('#sumRange').text(isNaN(r.from) || isNaN(r.to) ? '-' : rangeText(r.from, r.to));
    $('#sumMarkets').text(assets.length * exchanges.length);
    $('#sumBars').text(App.formatNumber(upbitMinutes + binanceMinutes));
    $('#sumTime').text(upbitMinutes + binanceMinutes === 0 ? '받을 것 없음' : minutes < 1 ? '1분 이내' : '약 ' + minutes + '분');

    var problem = !assets.length ? '종목을 하나 이상 고르세요.'
      : !exchanges.length ? '거래소를 하나 이상 고르세요.'
      : $('#dateFrom').val() > $('#dateTo').val() ? '시작일이 종료일보다 늦습니다.'
      : $('#dateTo').val() > maxDate ? '종료일은 ' + maxDate + ' (어제, UTC)까지 고를 수 있습니다.'
      : running ? '받는 중입니다.' : '';
    $('#submitBtn').prop('disabled', !!problem);
    $('#submitHint').text(problem);
  }

  // ---------- 입력 이벤트 ----------
  $('input[name="quick"]').on('change', function () {
    var to = new Date(maxDate);
    $('#dateTo').val(maxDate);
    $('#dateFrom').val(utcDate(to.getTime() - (Number(this.value) - 1) * DAY));
    updateSummary();
  });
  $('#dateFrom, #dateTo').on('change', function () { $('input[name="quick"]').prop('checked', false); });
  $(document).on('change input', '#rawForm input', updateSummary);

  // ---------- 받기 → 진행률 ----------
  $('#submitBtn').on('click', function () {
    var body = {
      assetIds: selected('asset').map(Number),
      exchanges: selected('exchange'),
      from: $('#dateFrom').val(),
      to: $('#dateTo').val(),
      includeWarmup: $('#includeWarmup').prop('checked')
    };
    running = true;
    $('#submitBtn').prop('disabled', true).text('받는 중…');
    App.api('POST', '/api/raw/collect', body)
      .done(function (res) {
        // 받은 기간 그대로 데이터세트 만들기 화면으로 넘긴다
        $('#doneLink').attr('href', '/datasets/new?' + $.param({ assets: body.assetIds.join(','), sources: body.exchanges.join(','), from: body.from, to: body.to }));
        startProgress(res.jobId);
      })
      .fail(function () {
        running = false;
        $('#submitBtn').text('받기');
        updateSummary();
      });
  });

  function startProgress(jobId) {
    running = true;
    $('#submitBtn').prop('disabled', true).text('받는 중…');
    $('#progressBox').show();
    $('#failBox, #doneBox').hide();
    $('#cancelBtn').attr('data-cancel-job', jobId).prop('disabled', false).text('■ 중지').show();
    poll(jobId);
  }

  function poll(jobId) {
    App.api('GET', '/api/jobs/' + jobId).done(function (job) {
      renderProgress(job);
      if (job.status === 'RUNNING') {
        setTimeout(function () { poll(jobId); }, 2000);
      } else {
        running = false;
        $('#cancelBtn').hide();
        $('#submitBtn').text('받기');
        loadCoverage();
        loadLogs();
      }
    });
  }

  function renderProgress(job) {
    var done = job.status === 'SUCCEEDED', failed = job.status === 'FAILED';
    $('#progressBar').css('width', job.progress + '%');
    $('#progressText').text(done ? '100% · 완료' : App.jobText(job));
    if (done) {
      $('#progressTag').attr('class', 'tag tag-green').html('<span class="dot"></span>완료');
      $('#doneBox').show();
    } else if (failed) {
      $('#progressTag').attr('class', 'tag tag-red').html('<span class="dot"></span>실패');
      $('#failText').text(job.message || '알 수 없는 오류');
      $('#failBox').show();
    } else {
      $('#progressTag').attr('class', 'tag tag-blue').html('<span class="dot"></span>진행 중');
    }
  }

  // ---------- 다른 화면에서 넘어온 값 (?assets=1,2&exchanges=UPBIT&from=&to=) ----------
  var q = new URLSearchParams(location.search);
  if (q.get('from') && q.get('to')) {
    var assets = (q.get('assets') || '').split(','), exchanges = (q.get('exchanges') || '').split(',');
    if (q.get('assets')) $('input[name="asset"]').each(function () { this.checked = assets.indexOf(this.value) >= 0; });
    if (q.get('exchanges')) $('input[name="exchange"]').each(function () { this.checked = exchanges.indexOf(this.value) >= 0; });
    $('#dateFrom').val(q.get('from'));
    $('#dateTo').val(q.get('to') > maxDate ? maxDate : q.get('to'));
    $('#includeWarmup').prop('checked', false); // 넘어온 시작일에 이미 워밍업이 들어 있다
    $('input[name="quick"]').prop('checked', false);
  }

  // 화면을 다시 열었을 때 받는 중인 작업이 있으면 진행률을 이어서 보여준다
  var runningJob = $form.data('running-job');
  if (runningJob) startProgress(runningJob);

  loadCoverage();
  loadLogs();
  updateSummary();
});

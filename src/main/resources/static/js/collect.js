/* 데이터 가져오기 화면 */
$(function () {
  var STEPS = ['원본 수집', '재집계', '지표 계산', '품질 검사', '완료'];
  var BAR_COLUMNS = 6;
  var maxDate = $('#collectForm').data('max-date');

  function selected(name) {
    return $('input[name="' + name + '"]:checked').map(function () { return this.value; }).get();
  }

  // ---------- 지표 조건: 필요한 데이터가 없으면 비활성화 ----------
  function applyRequirements() {
    var sources = selected('source'), assets = selected('asset');
    var ok = {
      BINANCE_CANDLES: sources.indexOf('BINANCE') >= 0,
      BOTH_EXCHANGES: sources.indexOf('BINANCE') >= 0 && sources.indexOf('UPBIT') >= 0,
      BOTH_ASSETS: assets.length === 2
    };
    $('.check-row').each(function () {
      var reqs = String($(this).data('requires') || '').split(',').filter(Boolean);
      var enabled = reqs.every(function (r) { return ok[r]; });
      $(this).toggleClass('is-disabled', !enabled);
      $(this).find('input').prop('disabled', !enabled);
      $(this).find('.need').html(enabled ? '' : '<span class="tag tag-yellow"></span>');
      if (!enabled) $(this).find('.need .tag').text($(this).data('need'));
    });
  }

  function updateGroups() {
    var total = 0, cols = 0;
    $('#featureGroups details').each(function () {
      var $rows = $(this).find('.check-row:not(.is-disabled)');
      var $on = $rows.filter(function () { return $(this).find('input').prop('checked'); });
      $(this).find('.toggle-meta').text($on.length + '/' + $(this).find('.check-row').length + ' 선택');
      var $g = $(this).find('.group-check');
      $g.prop('checked', $on.length === $rows.length && $on.length > 0);
      $g.prop('indeterminate', $on.length > 0 && $on.length < $rows.length);
      total += $on.length;
      $on.each(function () { cols += Number($(this).data('cols')); });
    });
    $('#featureCount').text(total + '개 선택');
    return cols;
  }

  function days() {
    var a = new Date($('#dateFrom').val()), b = new Date($('#dateTo').val());
    return Math.max(1, Math.round((b - a) / 86400000) + 1);
  }

  function updateSummary() {
    applyRequirements();
    var cols = updateGroups();
    var nAssets = selected('asset').length, d = days();
    var perMinute = selected('interval').reduce(function (s, v) { return s + 1 / parseInt(v, 10); }, 0);
    var rows = Math.round(nAssets * d * 1440 * perMinute);
    var calls = selected('source').indexOf('UPBIT') >= 0 ? Math.ceil(nAssets * d * 1440 / 200) : 0;
    var minutes = Math.max(1, Math.round(calls / 8 / 60 + d / 20 + rows / 400000));
    $('#sumRows').text(App.formatNumber(rows));
    $('#sumCols').text(BAR_COLUMNS + cols);
    $('#sumCalls').text(App.formatNumber(calls));
    $('#sumTime').text('약 ' + minutes + '분');

    var problem = !nAssets ? '종목을 하나 이상 고르세요.'
      : !selected('source').length ? '가져올 데이터를 하나 이상 고르세요.'
      : !selected('interval').length ? '봉 간격을 하나 이상 고르세요.'
      : $('#dateFrom').val() > $('#dateTo').val() ? '시작일이 종료일보다 늦습니다.'
      : !$.trim($('#datasetName').val()) ? '데이터세트 이름을 입력하세요.' : '';
    $('#submitBtn').prop('disabled', !!problem || running);
    $('#submitHint').text(problem);
  }

  // ---------- DB 에 이미 있는 기간 ----------
  App.api('GET', '/api/coverage').done(function (list) {
    $('.coverage').each(function () {
      var ex = $(this).data('exchange');
      var mine = list.filter(function (m) { return m.exchange === ex && m.first; });
      if (!mine.length) { $(this).text('DB 없음'); return; }
      var first = mine.map(function (m) { return m.first; }).sort()[0];
      var last = mine.map(function (m) { return m.last; }).sort().reverse()[0];
      $(this).text('DB ' + first + ' ~ ' + last);
    });
  });

  // ---------- 입력 이벤트 ----------
  function autoName() {
    var codes = $('input[name="asset"]:checked').map(function () { return $(this).data('code'); }).get().join('·');
    var label = { 7: '7일', 30: '1개월', 182: '6개월', 730: '24개월' }[$('input[name="quick"]:checked').val()] || (days() + '일');
    $('#datasetName').val((codes || '종목') + ' ' + label + ' 지표');
  }

  $('input[name="quick"]').on('change', function () {
    var to = new Date(maxDate);
    var from = new Date(to.getTime() - (Number(this.value) - 1) * 86400000);
    $('#dateTo').val(String(maxDate));
    $('#dateFrom').val(from.toISOString().slice(0, 10));
    autoName();
    updateSummary();
  });
  $('#dateFrom, #dateTo').on('change', function () { $('input[name="quick"]').prop('checked', false); });
  $('input[name="asset"]').on('change', autoName);

  $('.group-check').on('click', function (e) {
    e.stopPropagation();
    $(this).closest('details').find('.check-row:not(.is-disabled) input').prop('checked', this.checked);
    updateSummary();
  });
  $('#checkAll, #uncheckAll').on('click', function () {
    $('.check-row:not(.is-disabled) input').prop('checked', this.id === 'checkAll');
    updateSummary();
  });
  $(document).on('change input', '#collectForm input', updateSummary);

  // ---------- 받기 → 진행률 ----------
  var running = false;

  $('#submitBtn').on('click', function () {
    var body = {
      name: $.trim($('#datasetName').val()),
      assetIds: selected('asset').map(Number),
      from: $('#dateFrom').val(),
      to: $('#dateTo').val(),
      sources: selected('source'),
      intervals: selected('interval'),
      features: $('.check-row:not(.is-disabled) input:checked').map(function () { return this.value; }).get()
    };
    running = true;
    $('#submitBtn').prop('disabled', true).text('받는 중…');
    App.api('POST', '/api/datasets', body)
      .done(function (res) {
        $('#progressBox').show();
        $('#failBox, #doneBox').hide();
        $('#doneLink').attr('href', '/datasets/' + res.datasetId);
        $('html, body').animate({ scrollTop: $('#progressBox').offset().top - 60 }, 300);
        poll(res.jobId);
      })
      .fail(function () {
        running = false;
        $('#submitBtn').text('받기');
        updateSummary();
      });
  });

  function poll(jobId) {
    App.api('GET', '/api/jobs/' + jobId).done(function (job) {
      renderProgress(job);
      if (job.status === 'RUNNING') {
        setTimeout(function () { poll(jobId); }, 2000);
      } else {
        running = false;
        $('#submitBtn').text('받기');
        updateSummary();
      }
    });
  }

  function renderProgress(job) {
    var p = job.progress, done = job.status === 'SUCCEEDED', failed = job.status === 'FAILED';
    var now = done ? STEPS.length - 1 : Math.max(0, STEPS.indexOf(job.step));
    $('#progressBar').css('width', p + '%');
    $('#progressText').text(p + '% · ' + (job.step || '') + (job.message && !failed ? ' · ' + job.message : ''));
    $('#steps li').each(function (i) {
      var isDone = done || i < now, isNow = !done && i === now;
      $(this).toggleClass('done', isDone).toggleClass('now', isNow && !failed);
      $(this).find('.mark').text(isDone ? '✓' : isNow ? (failed ? '✕' : '●') : '○');
      $(this).find('.step-meta').text(isNow && !failed ? (job.message || '') : '');
    });
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

  // ---------- 같은 설정으로 새로 만들기 (?copy=데이터세트ID) ----------
  var copyId = new URLSearchParams(location.search).get('copy');
  if (copyId) {
    App.api('GET', '/api/datasets/' + copyId).done(function (d) {
      $('input[name="asset"]').each(function () { this.checked = d.assetIds.indexOf(Number(this.value)) >= 0; });
      $('input[name="source"]').each(function () { this.checked = d.sources.indexOf(this.value) >= 0; });
      $('input[name="interval"]').each(function () { this.checked = d.intervals.indexOf(this.value) >= 0; });
      $('input[name="feature"]').each(function () { this.checked = d.featureCodes.indexOf(this.value) >= 0; });
      $('input[name="quick"]').prop('checked', false);
      $('#dateFrom').val(d.fromDate);
      $('#dateTo').val(d.toDate > String(maxDate) ? String(maxDate) : d.toDate);
      $('#datasetName').val(d.summary.name + ' (다시)');
      updateSummary();
    });
  }

  updateSummary();
});

/* 새 원본 받기 */
$(function () {
  var maxDate = String($('#rawForm').data('max-date'));
  var running = false;

  function selected(name) {
    return $('input[name="' + name + '"]:checked').map(function () { return this.value; }).get();
  }

  function days() {
    return Math.max(1, App.daysBetween($('#dateFrom').val(), $('#dateTo').val()) + 1);
  }

  function exchangeLabel() {
    return $('input[name="exchange"]:checked').closest('label').contents().filter(function () { return this.nodeType === 3; }).text().trim();
  }

  function autoName() {
    var codes = $('input[name="asset"]:checked').map(function () { return $(this).data('code'); }).get().join('·');
    var q = { 7: '7일', 30: '1개월', 182: '6개월', 365: '1년', 730: '2년' }[$('input[name="quick"]:checked').val()] || (days() + '일');
    $('#name').val(exchangeLabel() + ' ' + (codes || '종목') + ' ' + q);
  }

  function update() {
    var n = selected('asset').length, d = days();
    var rows = n * d * 1440;
    var upbit = selected('exchange')[0] === 'UPBIT';
    var calls = upbit ? Math.ceil(rows / 200) : 0;
    var minutes = Math.max(1, Math.round(upbit ? calls / 8 / 60 + rows / 300000 : d / 30 + rows / 300000));
    $('#sumRows').text(App.formatNumber(rows));
    $('#sumCalls').text(upbit ? App.formatNumber(calls) : '해당 없음');
    $('#sumTime').text('약 ' + minutes + '분');
    var problem = !n ? '종목을 하나 이상 고르세요.'
      : $('#dateFrom').val() > $('#dateTo').val() ? '시작일이 종료일보다 늦습니다.'
      : $('#dateTo').val() > maxDate ? '종료일은 어제(' + maxDate + ')까지입니다.'
      : !$.trim($('#name').val()) ? '이름을 입력하세요.' : '';
    $('#submitBtn').prop('disabled', !!problem || running);
    $('#submitHint').text(problem);
  }

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

  $('input[name="quick"]').on('change', function () {
    $('#dateTo').val(maxDate);
    $('#dateFrom').val(App.addDays(maxDate, -(Number(this.value) - 1)));
    autoName();
    update();
  });
  $('#dateFrom, #dateTo').on('change', function () { $('input[name="quick"]').prop('checked', false); });
  $('input[name="asset"], input[name="exchange"]').on('change', autoName);
  $(document).on('change input', '#rawForm input', update);
  update();

  $('#submitBtn').on('click', function () {
    running = true;
    $('#submitBtn').prop('disabled', true).text('받는 중…');
    App.api('POST', '/api/raw', {
      name: $.trim($('#name').val()),
      exchange: selected('exchange')[0],
      assetIds: selected('asset').map(Number),
      from: $('#dateFrom').val(),
      to: $('#dateTo').val()
    }).done(function (res) {
      $('html, body').animate({ scrollTop: $('#jobBox').offset().top - 60 }, 300);
      App.watchJob($('#jobBox'), res.jobId, function (job) {
        if (job.status === 'SUCCEEDED') window.location.href = '/raw/' + res.id;
        else { running = false; $('#submitBtn').text('받기'); update(); }
      });
    }).fail(function () {
      running = false;
      $('#submitBtn').text('받기');
      update();
    });
  });
});

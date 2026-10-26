/* 학습 데이터 목록 화면 */
$(function () {
  var all = [];
  var timer = null;

  function statusTag(t) {
    if (t.building) return App.tag((t.step || t.statusLabel) + ' ' + t.progress + '%', 'blue');
    return App.tag(t.statusLabel, t.status === 'READY' ? 'green' : t.status === 'FAILED' ? 'red' : '');
  }

  function render() {
    var $tb = $('#setTable tbody').empty();
    all.forEach(function (t) {
      var $tr = $('<tr class="clickable"></tr>').attr('data-id', t.id);
      $tr.append($('<td></td>').append($('<span class="row-title"></span>').text('🏷️ ' + t.name)));
      $tr.append($('<td></td>').append(statusTag(t)));
      $tr.append($('<td class="muted"></td>').text(t.datasetName));
      var $assets = $('<td></td>');
      t.assets.forEach(function (a) { $assets.append($('<span class="tag"></span>').text(a)).append(' '); });
      $tr.append($assets);
      $tr.append($('<td></td>').text(t.interval + (t.extraIntervals.length ? ' (+' + t.extraIntervals.join('·') + ')' : '')));
      $tr.append($('<td class="num"></td>').text('±' + t.thresholdPct + '%'));
      $tr.append($('<td class="num small"></td>').html(
        $('<div></div>').text('학 ' + t.trainPeriod).prop('outerHTML') +
        $('<div></div>').text('검 ' + t.valPeriod).prop('outerHTML') +
        $('<div></div>').text('테 ' + t.testPeriod).prop('outerHTML')));
      $tr.append($('<td class="num"></td>').text(t.status === 'READY' ? App.formatNumber(t.rowCount) + ' × ' + t.columnCount : '-'));
      $tr.append($('<td class="num"></td>').text(t.runCount || ''));
      $tr.append($('<td class="muted"></td>').text(t.createdAt));
      var $act = $('<td style="white-space:nowrap"></td>');
      if (t.status === 'READY') $act.append($('<a class="btn btn-ghost btn-sm">🧠 학습</a>').attr('href', '/training?trainingSetId=' + t.id));
      if (!t.building) $act.append('<button class="btn btn-danger-ghost btn-sm del">삭제</button>');
      $tr.append($act);
      $tb.append($tr);
    });
    $('#emptyBox').toggle(all.length === 0);
    $('#setTable').closest('.db-table-wrap').toggle(all.length > 0);

    var $notes = $('#failNotes').empty();
    all.filter(function (t) { return t.status === 'FAILED' && t.message; }).slice(0, 3).forEach(function (t) {
      var $c = $('<div class="callout callout-red" style="margin-top:12px"><div class="callout-icon">⚠️</div><div class="callout-body"></div></div>');
      $c.find('.callout-body').append($('<b></b>').text(t.name)).append(document.createTextNode(' 실패: ' + t.message));
      $notes.append($c);
    });
  }

  function load() {
    App.api('GET', '/api/trainsets').done(function (list) {
      all = list;
      render();
      clearTimeout(timer);
      if (list.some(function (t) { return t.building; })) timer = setTimeout(load, 2000);
    });
  }

  $('#setTable').on('click', 'tr.clickable', function (e) {
    if ($(e.target).closest('button, a').length) return;
    location.href = '/trainsets/' + $(this).data('id');
  });
  $('#setTable').on('click', '.del', function () {
    var id = $(this).closest('tr').data('id');
    var t = all.filter(function (x) { return x.id === id; })[0];
    var extra = t.runCount ? ' 이 데이터로 만든 실험 ' + t.runCount + '개도 함께 지워집니다.' : '';
    if (!window.confirm('「' + t.name + '」을(를) 지울까요?' + extra + ' (기준 데이터세트는 남습니다)')) return;
    App.api('DELETE', '/api/trainsets/' + id).done(function () { App.toast('삭제했습니다.'); load(); });
  });

  load();
});

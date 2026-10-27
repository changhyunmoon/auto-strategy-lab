/* 데이터세트 목록 화면 */
$(function () {
  var all = [];
  var timer = null;

  function group(d) {
    return d.status === 'READY' ? 'READY' : d.status === 'FAILED' ? 'FAILED' : 'RUNNING';
  }

  function statusTag(d) {
    var g = group(d);
    var cls = g === 'READY' ? 'tag-green' : g === 'FAILED' ? 'tag-red' : 'tag-blue';
    var text = g === 'RUNNING' ? (d.step || d.statusLabel) + ' ' + d.progress + '%' : d.statusLabel;
    return $('<span class="tag"><span class="dot"></span></span>').addClass(cls).append(document.createTextNode(text));
  }

  function tags(list) {
    var $wrap = $('<span></span>');
    list.forEach(function (t) { $wrap.append($('<span class="tag"></span>').text(t)).append(' '); });
    return $wrap;
  }

  function render() {
    var s = $('input[name="status"]:checked').val(), q = $.trim($('#search').val());
    var counts = { ALL: all.length, READY: 0, RUNNING: 0, FAILED: 0 };
    all.forEach(function (d) { counts[group(d)]++; });
    Object.keys(counts).forEach(function (k) { $('[data-count="' + k + '"]').text(counts[k]); });

    var $tb = $('#datasetTable tbody').empty();
    var shown = all.filter(function (d) {
      return (s === 'ALL' || group(d) === s) && (!q || d.name.indexOf(q) >= 0);
    });
    shown.forEach(function (d) {
      var $tr = $('<tr class="clickable"></tr>').attr('data-id', d.id);
      $tr.append($('<td></td>').append($('<span class="row-title"></span>').text('🗂️ ' + d.name)));
      $tr.append($('<td></td>').append(statusTag(d)));
      $tr.append($('<td></td>').append(tags(d.assets)));
      $tr.append($('<td class="num"></td>').text(d.period));
      $tr.append($('<td></td>').append(tags(d.intervals)));
      $tr.append($('<td class="num"></td>').text(d.columnCount));
      $tr.append($('<td class="num"></td>').text(d.status === 'READY' ? App.formatNumber(d.rowCount) : '-').toggleClass('muted', d.status !== 'READY'));
      $tr.append($('<td class="muted"></td>').text(d.createdAt));
      $tr.append($('<td style="white-space:nowrap"></td>').append(d.building ? (d.jobId ? App.cancelButton(d.jobId) : '')
        : '<button class="btn btn-danger-ghost btn-sm del">삭제</button>'));
      $tb.append($tr);
    });
    $('#emptyBox').toggle(all.length === 0);
    $('#datasetTable').closest('.db-table-wrap').toggle(all.length > 0);
    $('#countText').text(all.length ? shown.length + '개' : '');

    var $notes = $('#failNotes').empty();
    all.filter(function (d) { return d.status === 'FAILED' && d.message; }).slice(0, 3).forEach(function (d) {
      var $c = $('<div class="callout callout-red" style="margin-top:12px"><div class="callout-icon">⚠️</div><div class="callout-body"></div></div>');
      $c.find('.callout-body').append($('<b></b>').text(d.name)).append(document.createTextNode(' 실패: ' + d.message));
      $notes.append($c);
    });
  }

  function load() {
    App.api('GET', '/api/datasets').done(function (list) {
      all = list;
      render();
      clearTimeout(timer);
      if (list.some(function (d) { return d.building; })) {
        timer = setTimeout(load, 3000); // 진행 중인 항목이 있으면 3초마다 갱신
      }
    });
  }

  $(document).on('job:cancel-requested', function () { load(); });
  $('input[name="status"]').on('change', render);
  $('#search').on('input', render);
  $('#datasetTable').on('click', 'tr.clickable', function (e) {
    if ($(e.target).closest('button').length) return;
    location.href = '/datasets/' + $(this).data('id');
  });
  $('#datasetTable').on('click', '.del', function () {
    var id = $(this).closest('tr').data('id');
    var d = all.filter(function (x) { return x.id === id; })[0];
    if (!window.confirm('「' + d.name + '」을(를) 지울까요? 데이터세트 행이 모두 삭제됩니다. (받아 둔 1분봉 원본은 남습니다)')) return;
    App.api('DELETE', '/api/datasets/' + id).done(function () {
      App.toast('삭제했습니다.', 'blue');
      load();
    });
  });

  load();
});

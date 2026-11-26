/* LSDFMS — Cliente Web. JavaScript nativo, sin librerías externas (RNF-01). */

(function () {
  'use strict';

  // ?api=http://IP:8080 permite apuntar a la VM de la nube.
  var API = new URLSearchParams(location.search).get('api') || '';

  // Replica Metadata.indexedColumns.
  var COLUMNAS_INDEXADAS = ['id', 'dni', 'departamento', 'tipoSeguro'];

  var DEPARTAMENTOS = [
    'Amazonas', 'Áncash', 'Apurímac', 'Arequipa', 'Ayacucho', 'Cajamarca', 'Callao',
    'Cusco', 'Huancavelica', 'Huánuco', 'Ica', 'Junín', 'La Libertad', 'Lambayeque',
    'Lima', 'Loreto', 'Madre de Dios', 'Moquegua', 'Pasco', 'Piura', 'Puno',
    'San Martín', 'Tacna', 'Tumbes', 'Ucayali'
  ];

  var STATUS_POLL_MS = 3000;

  var state = {
    page: 0,
    size: 25,
    estado: 'activos',
    hasMore: false,
    indexSynced: null,
    highlightOffset: null,
    busy: false
  };

  var $ = function (id) { return document.getElementById(id); };

  function fmtNumero(n) {
    if (n === null || n === undefined || isNaN(n)) return '—';
    return Number(n).toLocaleString('es-PE');
  }

  function fmtSoles(n) {
    return 'S/ ' + Number(n).toLocaleString('es-PE', {
      minimumFractionDigits: 2, maximumFractionDigits: 2
    });
  }

  function fmtHora(ms) {
    if (!ms) return '—';
    var d = new Date(Number(ms));
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleString('es-PE', { hour12: false });
  }

  function log(nivel, mensaje) {
    var ul = $('activityLog');
    var li = document.createElement('li');
    var ts = document.createElement('span');
    ts.className = 'ts';
    ts.textContent = new Date().toLocaleTimeString('es-PE', { hour12: false });
    var txt = document.createElement('span');
    txt.className = 'lvl-' + nivel;
    txt.textContent = mensaje;
    li.appendChild(ts);
    li.appendChild(txt);
    ul.insertBefore(li, ul.firstChild);
    while (ul.children.length > 120) {
      ul.removeChild(ul.lastChild);
    }
  }

  var toastTimer = null;
  function toast(nivel, mensaje) {
    var el = $('toast');
    el.textContent = mensaje;
    el.className = 'toast ' + nivel;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.add('hidden'); }, 4000);
  }

  function aviso(nivel, mensaje) {
    log(nivel, mensaje);
    toast(nivel, mensaje);
  }

  function api(path, options) {
    return fetch(API + path, options).then(function (res) {
      return res.text().then(function (texto) {
        var cuerpo;
        try {
          cuerpo = texto ? JSON.parse(texto) : {};
        } catch (e) {
          cuerpo = { success: false, message: texto || 'Respuesta no válida del servidor' };
        }
        return { ok: res.ok, status: res.status, body: cuerpo };
      });
    });
  }

  function pintarEstadoServidor(conectado) {
    var el = $('serverState');
    el.textContent = conectado ? 'Servidor: en línea' : 'Servidor: sin conexión';
    el.className = 'badge ' + (conectado ? 'badge-ok' : 'badge-danger');
  }

  function pintarEstadoIndice(sincronizado) {
    var el = $('indexState');
    if (sincronizado) {
      el.textContent = 'Índice: Sincronizado';
      el.className = 'badge badge-ok';
    } else {
      el.textContent = 'Índice: Desactualizado';
      el.className = 'badge badge-warn pulsing';
    }
    $('btnMantenimiento').disabled = state.busy;
  }

  function cargarEstado() {
    return api('/api/status', { method: 'GET' }).then(function (r) {
      if (!r.ok) throw new Error(r.body.message || ('HTTP ' + r.status));
      var m = r.body;
      pintarEstadoServidor(true);

      $('mTotal').textContent = fmtNumero(m.totalRecords);
      $('mActivos').textContent = fmtNumero(m.activeRecords);
      $('mEliminados').textContent = fmtNumero(m.deletedRecords);
      $('mCola').textContent = fmtNumero(m.currentQueueSize);
      $('mOffset').textContent = fmtNumero(m.lastOffset);
      $('mCompactado').textContent = fmtHora(m.lastCompactedAt);
      $('mIndexado').textContent = fmtHora(m.lastIndexedAt);

      if (state.indexSynced !== null && state.indexSynced !== m.isIndexSynced) {
        log(m.isIndexSynced ? 'ok' : 'warn',
          m.isIndexSynced
            ? 'Los índices volvieron a estado Sincronizado.'
            : 'Los datos fueron alterados: el índice quedó Desactualizado.');
      }
      state.indexSynced = m.isIndexSynced;
      pintarEstadoIndice(m.isIndexSynced);
      return m;
    }).catch(function (e) {
      pintarEstadoServidor(false);
      $('indexState').textContent = 'Índice: —';
      $('indexState').className = 'badge badge-unknown';
      throw e;
    });
  }

  function celda(texto, clase) {
    var td = document.createElement('td');
    td.textContent = texto;
    if (clase) td.className = clase;
    return td;
  }

  function filaRegistro(p) {
    var tr = document.createElement('tr');
    if (p.deleted) tr.classList.add('deleted');
    if (state.highlightOffset !== null && Number(p.offset) === state.highlightOffset) {
      tr.classList.add('highlight');
    }

    tr.appendChild(celda(p.offset, 'offset'));
    tr.appendChild(celda(p.id, 'num'));
    tr.appendChild(celda(p.dni));
    tr.appendChild(celda(p.titular, 'cell-titular'));
    tr.appendChild(celda(p.tipoSeguro));
    tr.appendChild(celda(p.departamento));
    tr.appendChild(celda(fmtSoles(p.prima), 'num'));
    tr.appendChild(celda(p.fechaEmision));

    var tdEstado = document.createElement('td');
    var pill = document.createElement('span');
    pill.className = 'pill ' + (p.deleted ? 'pill-del' : 'pill-ok');
    pill.textContent = p.deleted ? 'Marcado' : 'Activo';
    tdEstado.appendChild(pill);
    tr.appendChild(tdEstado);

    var tdAcciones = document.createElement('td');
    var acciones = document.createElement('div');
    acciones.className = 'row-actions';

    var bEditar = document.createElement('button');
    bEditar.className = 'btn btn-ghost btn-mini';
    bEditar.textContent = 'Editar';
    bEditar.disabled = !!p.deleted;
    bEditar.addEventListener('click', function () { cargarEnFormulario(p); });
    acciones.appendChild(bEditar);

    var bBorrar = document.createElement('button');
    bBorrar.className = 'btn btn-danger btn-mini';
    bBorrar.textContent = 'Eliminar';
    bBorrar.disabled = !!p.deleted;
    bBorrar.addEventListener('click', function () { eliminarRegistro(p); });
    acciones.appendChild(bBorrar);

    tdAcciones.appendChild(acciones);
    tr.appendChild(tdAcciones);
    return tr;
  }

  function mensajeTabla(texto) {
    var tbody = $('policyTableBody');
    tbody.replaceChildren();
    var tr = document.createElement('tr');
    var td = document.createElement('td');
    td.colSpan = 10;
    td.className = 'empty';
    td.textContent = texto;
    tr.appendChild(td);
    tbody.appendChild(tr);
  }

  function cargarTabla() {
    var url = '/api/policies/list?page=' + state.page +
              '&size=' + state.size +
              '&estado=' + encodeURIComponent(state.estado);

    return api(url, { method: 'GET' }).then(function (r) {
      if (!r.ok) throw new Error(r.body.message || ('HTTP ' + r.status));

      var registros = r.body.records || [];
      state.hasMore = !!r.body.hasMore;

      if (registros.length === 0) {
        mensajeTabla(state.page > 0
          ? 'No hay más registros en esta página.'
          : 'El archivo de datos no contiene registros para este filtro.');
      } else {
        var tbody = $('policyTableBody');
        tbody.replaceChildren();
        registros.forEach(function (p) { tbody.appendChild(filaRegistro(p)); });
      }

      var desde = state.page * state.size + (registros.length ? 1 : 0);
      var hasta = state.page * state.size + registros.length;
      $('pageInfo').textContent = registros.length
        ? ('Registros ' + fmtNumero(desde) + '–' + fmtNumero(hasta) +
           ' · página ' + (state.page + 1))
        : ('Página ' + (state.page + 1));

      $('btnPrev').disabled = state.page === 0;
      $('btnNext').disabled = !state.hasMore;
    }).catch(function (e) {
      mensajeTabla('No se pudo cargar el listado: ' + e.message);
      $('btnNext').disabled = true;
    });
  }

  function refrescar() {
    return Promise.all([
      cargarEstado().catch(function () {}),
      cargarTabla()
    ]);
  }

  function leerFormulario() {
    return {
      id: Number($('id').value),
      dni: $('dni').value.trim(),
      titular: $('titular').value.trim(),
      tipoSeguro: $('tipoSeguro').value,
      departamento: $('departamento').value,
      prima: Number($('prima').value),
      fechaEmision: $('fechaEmision').value
    };
  }

  function cargarEnFormulario(p) {
    $('editOffset').value = p.offset;
    $('id').value = p.id;
    $('dni').value = p.dni;
    $('titular').value = p.titular;
    $('tipoSeguro').value = p.tipoSeguro;
    $('departamento').value = p.departamento;
    $('prima').value = p.prima;
    $('fechaEmision').value = p.fechaEmision;

    $('formTitle').textContent = 'Modificar póliza (offset ' + p.offset + ')';
    $('btnGuardar').textContent = 'Encolar modificación';
    $('btnCancelar').classList.remove('hidden');
    $('panelFormulario').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function salirDeEdicion() {
    $('policyForm').reset();
    $('editOffset').value = '';
    $('formTitle').textContent = 'Registrar nueva póliza';
    $('btnGuardar').textContent = 'Encolar inserción';
    $('btnCancelar').classList.add('hidden');
    $('fechaEmision').value = new Date().toISOString().slice(0, 10);
  }

  function guardarPoliza(evento) {
    evento.preventDefault();
    if (state.busy) return;

    var offsetEdicion = $('editOffset').value;
    var esEdicion = offsetEdicion !== '';
    var cuerpo = JSON.stringify(leerFormulario());

    var ruta = esEdicion
      ? '/api/policies?offset=' + encodeURIComponent(offsetEdicion)
      : '/api/policies';

    bloquear(true);
    api(ruta, {
      method: esEdicion ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: cuerpo
    }).then(function (r) {
      if (!r.ok || r.body.success === false) {
        aviso('error', 'Operación rechazada: ' + (r.body.message || ('HTTP ' + r.status)));
        return;
      }
      state.highlightOffset = Number(r.body.offset);
      aviso('ok', (esEdicion ? 'Modificación' : 'Inserción') +
        ' procesada desde la cola. Nuevo offset: ' + r.body.offset);
      salirDeEdicion();
      return refrescar();
    }).catch(function (e) {
      aviso('error', 'Error de red: ' + e.message);
    }).finally(function () {
      bloquear(false);
    });
  }

  function eliminarRegistro(p) {
    if (state.busy) return;
    if (!confirm('¿Marcar para borrado lógico la póliza ' + p.id +
                 ' (' + p.titular + ')?\nEl registro se eliminará físicamente al ejecutar PACK().')) {
      return;
    }

    bloquear(true);
    api('/api/policies?offset=' + encodeURIComponent(p.offset), { method: 'DELETE' })
      .then(function (r) {
        if (!r.ok || r.body.success === false) {
          aviso('error', 'No se pudo eliminar: ' + (r.body.message || ('HTTP ' + r.status)));
          return;
        }
        aviso('ok', 'Registro en offset ' + p.offset + ' marcado para borrado lógico.');
        return refrescar();
      })
      .catch(function (e) { aviso('error', 'Error de red: ' + e.message); })
      .finally(function () { bloquear(false); });
  }

  function tarjetaResultado(p) {
    var card = document.createElement('div');
    card.className = 'result-card';
    var dl = document.createElement('dl');

    var campos = [
      ['Offset físico', String(p.offset)],
      ['ID', String(p.id)],
      ['DNI', p.dni],
      ['Titular', p.titular],
      ['Tipo de seguro', p.tipoSeguro],
      ['Departamento', p.departamento],
      ['Prima', fmtSoles(p.prima)],
      ['Emisión', p.fechaEmision],
      ['Estado', p.deleted ? 'Marcado para borrado' : 'Activo']
    ];

    campos.forEach(function (par) {
      var dt = document.createElement('dt');
      dt.textContent = par[0];
      var dd = document.createElement('dd');
      dd.textContent = par[1];
      dl.appendChild(dt);
      dl.appendChild(dd);
    });

    card.appendChild(dl);
    return card;
  }

  function buscar(evento) {
    evento.preventDefault();
    var columna = $('searchColumn').value;
    var clave = $('searchKey').value.trim();
    if (!clave) return;

    var destino = $('searchResult');
    destino.replaceChildren();
    destino.textContent = 'Buscando…';

    var inicio = performance.now();
    api('/api/search?column=' + encodeURIComponent(columna) +
        '&key=' + encodeURIComponent(clave), { method: 'GET' })
      .then(function (r) {
        var ms = (performance.now() - inicio).toFixed(1);
        destino.replaceChildren();

        if (r.status === 404) {
          var vacio = document.createElement('p');
          vacio.className = 'result-empty';
          vacio.textContent = 'Sin coincidencias para ' + columna + ' = ' + clave +
            (state.indexSynced === false
              ? '. El índice está Desactualizado: ejecute el mantenimiento.'
              : '.');
          destino.appendChild(vacio);
          log('warn', 'Búsqueda sin resultados: ' + columna + ' = ' + clave);
          return;
        }
        if (!r.ok) {
          var err = document.createElement('p');
          err.className = 'result-empty';
          err.textContent = r.body.message || ('HTTP ' + r.status);
          destino.appendChild(err);
          log('error', 'Error en búsqueda: ' + (r.body.message || r.status));
          return;
        }

        destino.appendChild(tarjetaResultado(r.body));
        state.highlightOffset = Number(r.body.offset);
        log('ok', 'Búsqueda dicotómica ' + columna + ' = ' + clave +
                  ' → offset ' + r.body.offset + ' (' + ms + ' ms)');
      })
      .catch(function (e) {
        destino.textContent = 'Error de red: ' + e.message;
      });
  }

  function ejecutarMantenimiento() {
    if (state.busy) return;
    bloquear(true);
    var boton = $('btnMantenimiento');
    var etiqueta = boton.textContent;
    boton.textContent = 'Ejecutando PACK() e INDEX()…';
    log('info', 'Mantenimiento solicitado: PACK() y INDEX() en el servidor.');

    var inicio = performance.now();
    api('/api/maintenance', { method: 'POST' })
      .then(function (r) {
        var ms = (performance.now() - inicio).toFixed(0);
        if (!r.ok || r.body.success === false) {
          aviso('error', 'Mantenimiento con errores: ' + (r.body.message || ('HTTP ' + r.status)));
        } else {
          aviso('ok', 'Mantenimiento completado en ' + ms + ' ms. ' + (r.body.message || ''));
        }
        // PACK cambia todos los offsets: se recarga desde la primera página.
        state.page = 0;
        state.highlightOffset = null;
        return refrescar();
      })
      .catch(function (e) { aviso('error', 'Error de red: ' + e.message); })
      .finally(function () {
        boton.textContent = etiqueta;
        bloquear(false);
      });
  }

  function bloquear(activo) {
    state.busy = activo;
    $('btnGuardar').disabled = activo;
    $('btnMantenimiento').disabled = activo;
    $('btnRecargar').disabled = activo;
  }

  function poblarSelects() {
    var depSelect = $('departamento');
    DEPARTAMENTOS.forEach(function (d) {
      var opt = document.createElement('option');
      opt.value = d;
      opt.textContent = d;
      // defaultSelected sobrevive a form.reset(), a diferencia de select.value.
      if (d === 'Lima') opt.defaultSelected = true;
      depSelect.appendChild(opt);
    });

    var colSelect = $('searchColumn');
    COLUMNAS_INDEXADAS.forEach(function (c) {
      var opt = document.createElement('option');
      opt.value = c;
      opt.textContent = c;
      if (c === 'dni') opt.defaultSelected = true;
      colSelect.appendChild(opt);
    });
  }

  function conectarEventos() {
    $('policyForm').addEventListener('submit', guardarPoliza);
    $('btnCancelar').addEventListener('click', salirDeEdicion);
    $('searchForm').addEventListener('submit', buscar);
    $('btnLimpiarBusqueda').addEventListener('click', function () {
      $('searchKey').value = '';
      $('searchResult').replaceChildren();
    });
    $('btnMantenimiento').addEventListener('click', ejecutarMantenimiento);
    $('btnRecargar').addEventListener('click', function () { refrescar(); });
    $('btnLimpiarLog').addEventListener('click', function () {
      $('activityLog').replaceChildren();
    });

    $('filtroEstado').addEventListener('change', function () {
      state.estado = this.value;
      state.page = 0;
      cargarTabla();
    });
    $('tamPagina').addEventListener('change', function () {
      state.size = Number(this.value);
      state.page = 0;
      cargarTabla();
    });
    $('btnPrev').addEventListener('click', function () {
      if (state.page > 0) { state.page--; cargarTabla(); }
    });
    $('btnNext').addEventListener('click', function () {
      if (state.hasMore) { state.page++; cargarTabla(); }
    });
  }

  function iniciar() {
    poblarSelects();
    conectarEventos();
    salirDeEdicion();
    log('info', 'Cliente web iniciado contra ' + (API || location.origin));
    refrescar();
    setInterval(function () {
      cargarEstado().catch(function () {});
    }, STATUS_POLL_MS);
  }

  document.addEventListener('DOMContentLoaded', iniciar);
})();

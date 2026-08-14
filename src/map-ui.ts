/** 实时地图（Leaflet）：高德瓦片（普通地图 + 卫星，免 Key，国内秒开）+ OSM 兜底。
 * WGS-84 定位 → GCJ-02 再渲染（高德瓦片是火星坐标）；断网时纸色底，轨迹/标记照常绘制。 */

import L from 'leaflet';
import { wgs2gcj } from './wgs2gcj.js';

export interface MapController {
  /** 跟随当前位置（自车标记 + 精度圈 + 当前段实时增长） */
  follow(lat: number, lng: number, accM?: number): void;
  /** 整条轨迹线（分段：走过淡红、当前段实红粗线，像导航一样） */
  setTrack(pts: { lat: number; lng: number }[], breaks?: number[]): void;
  /** 户节点 + Home 标记 */
  setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void;
  /** 容器尺寸变化后重算（地图折叠/展开后调用） */
  invalidateSize(): void;
  destroy(): void;
}

export function mountMap(
  el: HTMLElement,
  initial: { lat: number; lng: number } | null,
): MapController {
  const map = L.map(el, {
    zoomControl: false, // 设计稿规范：手机用双指缩放，不显示会遮挡画面的 +/- 控件
    attributionControl: false, // 版权注由地图卡底部一行展示
    center: initial ? [initial.lat, initial.lng] : [36.71, 119.1], // 默认潍坊附近
    zoom: initial ? 16 : 13,
  });

  /* ---------- 图层：普通地图（高德 → OSM 兜底）+ 卫星（高德） ---------- */
  let useGcj = true;
  let streetSrc = 0;
  let streetErr = 0;
  let street: L.TileLayer | null = null;
  let sat: L.TileLayer | null = null;
  let curLayer: 'street' | 'sat' = 'street';

  const makeStreet = (): void => {
    const isGaode = streetSrc === 0;
    const layer = L.tileLayer(
      isGaode
        ? 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}'
        : 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      { maxZoom: 19, ...(isGaode ? { subdomains: '1234' } : {}) },
    );
    layer.on('tileerror', () => {
      streetErr += 1;
      if (streetErr > 6 && streetSrc === 0) {
        streetSrc = 1;
        streetErr = 0;
        useGcj = false; // OSM 是 WGS-84，切回原坐标
        layer.remove();
        makeStreet();
        redrawAll();
      }
    });
    street = layer;
    if (curLayer === 'street') layer.addTo(map);
  };

  const makeSat = (): void => {
    const layer = L.tileLayer(
      'https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
      { maxZoom: 19, subdomains: '1234' },
    );
    sat = layer;
    if (curLayer === 'sat') layer.addTo(map);
  };
  makeStreet();
  makeSat();

  // 图层切换按钮（注入地图容器右上角）
  const sw = document.createElement('div');
  sw.className = 'baibai-layer-switch';
  const btnStreet = document.createElement('button');
  btnStreet.textContent = '地图';
  btnStreet.className = 'on';
  const btnSat = document.createElement('button');
  btnSat.textContent = '卫星';
  sw.append(btnStreet, btnSat);
  el.appendChild(sw);
  const syncButtons = (): void => {
    btnStreet.classList.toggle('on', curLayer === 'street');
    btnSat.classList.toggle('on', curLayer === 'sat');
  };
  btnStreet.addEventListener('click', () => {
    if (sat && map.hasLayer(sat)) map.removeLayer(sat);
    if (street) street.addTo(map);
    curLayer = 'street';
    syncButtons();
  });
  btnSat.addEventListener('click', () => {
    if (street && map.hasLayer(street)) map.removeLayer(street);
    if (sat) sat.addTo(map);
    curLayer = 'sat';
    syncButtons();
  });

  const cvt = (p: [number, number]): [number, number] =>
    useGcj ? wgs2gcj(p[0], p[1]) : p;

  /* ---------- 轨迹（分段） ---------- */
  const trackPts: [number, number][] = [];
  const segBreaks: number[] = [0];
  const segGroup = L.layerGroup().addTo(map);

  function drawSegments(): void {
    segGroup.clearLayers();
    for (let i = 0; i < segBreaks.length; i++) {
      const a = segBreaks[i];
      const b = i + 1 < segBreaks.length ? segBreaks[i + 1] : trackPts.length;
      if (b - a < 2) continue;
      const pts: L.LatLngExpression[] = [];
      for (let k = a; k < b; k++) pts.push(cvt(trackPts[k]));
      const isCurrent = i === segBreaks.length - 1;
      L.polyline(pts, {
        color: '#c8402f',
        weight: isCurrent ? 5 : 4,
        opacity: isCurrent ? 0.95 : 0.35,
        lineJoin: 'round',
      }).addTo(segGroup);
    }
  }

  /* ---------- 当前位置 + 精度圈 ---------- */
  let me: L.CircleMarker | null = null;
  let accCircle: L.Circle | null = null;
  function drawMe(lat: number, lng: number, accM?: number): void {
    const c = cvt([lat, lng]);
    if (!me) {
      me = L.circleMarker(c, {
        radius: 9,
        color: '#ffffff',
        weight: 2.5,
        fillColor: '#c8402f',
        fillOpacity: 1,
      }).addTo(map);
    } else {
      me.setLatLng(c);
    }
    if (accCircle) {
      accCircle.remove();
      accCircle = null;
    }
    if (accM && accM > 0) {
      accCircle = L.circle(c, {
        radius: accM,
        color: '#c8402f',
        weight: 1,
        fillColor: '#c8402f',
        fillOpacity: 0.07,
        interactive: false,
      }).addTo(map);
    }
  }

  /* ---------- 家/户标记 ---------- */
  let rawHome: { lat: number; lng: number } | null = null;
  let rawNodes: { name: string; autoNo: number; pos: { lat: number; lng: number } }[] = [];
  let nodeLayer = L.layerGroup().addTo(map);

  function drawNodes(): void {
    const layer = L.layerGroup();
    if (rawHome && (rawHome.lat !== 0 || rawHome.lng !== 0)) {
      L.marker(cvt([rawHome.lat, rawHome.lng]), {
        icon: L.divIcon({
          className: 'baibai-home-icon',
          html: '<div class="home-pin">家</div>',
          iconSize: [30, 30],
          iconAnchor: [15, 15],
        }),
        zIndexOffset: 200,
      }).addTo(layer);
    }
    for (const n of rawNodes) {
      const m = L.marker(cvt([n.pos.lat, n.pos.lng]), {
        icon: L.divIcon({
          className: 'baibai-node-icon',
          html: '<div class="node-pin">' + n.autoNo + '</div>',
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
        zIndexOffset: 150,
      });
      // P11：户名为用户数据，用 textContent 容器防 XSS
      if (n.name) {
        const label = document.createElement('div');
        label.textContent = n.name;
        m.bindTooltip(label, { direction: 'top', offset: [0, -14] });
      }
      layer.addLayer(m);
    }
    nodeLayer.remove();
    nodeLayer = layer.addTo(map);
  }

  function redrawAll(): void {
    drawSegments();
    drawNodes();
  }

  function follow(lat: number, lng: number, accM?: number): void {
    trackPts.push([lat, lng]);
    drawSegments();
    drawMe(lat, lng, accM);
    map.setView(cvt([lat, lng]), Math.max(map.getZoom(), 16), { animate: true });
  }

  function setTrack(pts: { lat: number; lng: number }[], breaks?: number[]): void {
    trackPts.length = 0;
    for (const p of pts) trackPts.push([p.lat, p.lng]);
    segBreaks.length = 0;
    if (breaks && breaks.length > 0) segBreaks.push(...breaks);
    else segBreaks.push(0);
    drawSegments();
    if (trackPts.length > 1) {
      map.fitBounds(L.latLngBounds(trackPts.map((p) => cvt(p))).pad(0.25));
    }
  }

  function setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void {
    rawHome = home;
    rawNodes = nodes;
    drawNodes();
  }

  function destroy(): void {
    map.remove();
  }

  function invalidateSize(): void {
    map.invalidateSize();
  }

  return { follow, setTrack, setNodes, invalidateSize, destroy };
}

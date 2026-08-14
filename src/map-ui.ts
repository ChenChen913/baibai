/** 实时地图（Leaflet + OpenStreetMap）：当前定位、轨迹线、户节点、Home。断网时瓦片缺失但轨迹/节点仍正常绘制。 */

import L from 'leaflet';

export interface MapController {
  /** 跟随当前位置（含自车标记与精度圈） */
  follow(lat: number, lng: number, accM?: number): void;
  /** 整条轨迹线 */
  setTrack(pts: { lat: number; lng: number }[]): void;
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
    zoom: initial ? 17 : 13,
  });

  // 免 Key 免费瓦片：OSM 主源 → OSM-HOT 备源，失败自动切换；断网时轨迹/标记照常绘制
  const sources = [
    'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    'https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png',
  ];
  let srcIdx = 0;
  let errCount = 0;
  const addTile = (): void => {
    if (srcIdx >= sources.length) return;
    const layer = L.tileLayer(sources[srcIdx], {
      maxZoom: 19,
      ...(srcIdx === 1 ? { subdomains: 'abc' } : {}),
    });
    layer.on('tileerror', () => {
      errCount += 1;
      if (errCount > 6 && srcIdx < sources.length - 1) {
        errCount = 0;
        srcIdx += 1;
        layer.remove();
        addTile();
      }
    });
    layer.addTo(map);
  };
  addTile();

  const track = L.polyline([], {
    color: '#c8402f',
    weight: 4,
    opacity: 0.85,
    lineJoin: 'round',
  }).addTo(map);
  const trackPts: L.LatLngExpression[] = [];

  let me: L.CircleMarker | null = null;
  let accCircle: L.Circle | null = null;
  let homeMarker: L.Marker | null = null;
  let nodeLayer = L.layerGroup().addTo(map);

  function follow(lat: number, lng: number, accM?: number): void {
    if (!me) {
      me = L.circleMarker([lat, lng], {
        radius: 9,
        color: '#ffffff',
        weight: 2.5,
        fillColor: '#c8402f',
        fillOpacity: 1,
      }).addTo(map);
    } else {
      me.setLatLng([lat, lng]);
    }
    if (accCircle) {
      accCircle.remove();
      accCircle = null;
    }
    if (accM && accM > 0) {
      accCircle = L.circle([lat, lng], {
        radius: accM,
        color: '#c8402f',
        weight: 1,
        fillColor: '#c8402f',
        fillOpacity: 0.07,
        interactive: false,
      }).addTo(map);
    }
    trackPts.push([lat, lng]);
    track.setLatLngs(trackPts);
    map.setView([lat, lng], Math.max(map.getZoom(), 16), { animate: true });
  }

  function setTrack(pts: { lat: number; lng: number }[]): void {
    trackPts.length = 0;
    for (const p of pts) trackPts.push([p.lat, p.lng]);
    track.setLatLngs(trackPts);
  }

  function setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void {
    nodeLayer.clearLayers();
    if (homeMarker) {
      homeMarker.remove();
      homeMarker = null;
    }
    homeMarker = L.marker([home.lat, home.lng], {
      icon: L.divIcon({
        className: 'baibai-home-icon',
        html: '<div class="home-pin">家</div>',
        iconSize: [30, 30],
        iconAnchor: [15, 15],
      }),
      zIndexOffset: 200,
    }).addTo(map);
    for (const n of nodes) {
      const m = L.marker([n.pos.lat, n.pos.lng], {
        icon: L.divIcon({
          className: 'baibai-node-icon',
          html: `<div class="node-pin">${n.autoNo}</div>`,
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
        zIndexOffset: 150,
      });
      // P11：户名为用户数据，用 textContent 容器防 XSS（Leaflet 字符串 tooltip 走 innerHTML）
      if (n.name) {
        const label = document.createElement('div');
        label.textContent = n.name;
        m.bindTooltip(label, { direction: 'top', offset: [0, -14] });
      }
      nodeLayer.addLayer(m);
    }
  }

  function destroy(): void {
    map.remove();
  }

  function invalidateSize(): void {
    map.invalidateSize();
  }

  return { follow, setTrack, setNodes, invalidateSize, destroy };
}

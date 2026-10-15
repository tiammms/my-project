import { bio } from "../api";
import { Async, Card, PageHead, Stat, fmt, useAsync } from "../components/ui";
import { KIND, Lvl, ModelList, Privacy } from "../components/bio";

export function BioHome() {
  const s = useAsync(() => bio.summary(), []);
  return <>
    <PageHead title="Центр безопасности" desc="Проверка лица, голоса и содержания звонка вместе с оценкой перевода. Все модели работают на этом компьютере."
      right={<div className="toolbar" style={{ margin: 0 }}>
        <a className="btn primary" href="#/face">Проверить лицо</a><a className="btn" href="#/voice">Проверить звонок</a><a className="btn" href="#/combined">Общая проверка</a></div>} />
    <Privacy>Фото, видео и аудио обрабатываются в памяти и не сохраняются. Шаблон лица сохраняется только с вашего согласия и в зашифрованном виде.
      В истории остаются только результаты. Управление — в разделе <a href="#/privacy">«Настройки и конфиденциальность»</a>.</Privacy>
    <Async s={s}>{(d) => {
      const by = (lvl: string) => d.counts.by_kind_level.filter((r: any) => r.risk_level === lvl).reduce((a: number, r: any) => a + r.n, 0);
      const working = d.models.filter((m: any) => m.verified).length;
      return <>
        <div className="grid g4">
          <Stat label="Проверок в истории" value={fmt.int(d.counts.total)} note={`хранятся ${d.privacy.history_retention_days} дн.${d.privacy.store_history ? "" : " · запись истории выключена"}`} />
          <Stat label="Высокий риск" value={<span style={{ color: "var(--high)" }}>{fmt.int(by("HIGH"))}</span>} note={`средний: ${fmt.int(by("MEDIUM"))} · низкий: ${fmt.int(by("LOW"))}`} />
          <Stat label="Зарегистрированные лица" value={fmt.int(d.enrolled_subjects)} note="только с согласием, шифрование" />
          <Stat label="Модели работают" value={`${working} / ${d.models.length}`} note={working < d.models.length ? "см. список ниже" : "все установлены"} />
        </div>
        <div style={{ height: 16 }} />
        <div className="grid g2">
          <Card title="Последние проверки" right={<a className="small" href="#/history">Вся история →</a>}>
            {d.recent.length ? <div className="table-wrap"><table>
              <thead><tr><th>Время (UTC)</th><th>Тип</th><th>Риск</th><th>Итог</th></tr></thead>
              <tbody>{d.recent.map((r: any) => <tr key={r.check_id} className="click" onClick={() => { window.location.hash = "#/history/" + r.check_id; }}>
                <td className="small muted">{fmt.time(r.created_at)}</td><td>{KIND[r.kind] || r.kind}</td><td><Lvl level={r.risk_level} /></td>
                <td className="small clip" style={{ maxWidth: 260 }} title={r.summary}>{r.summary}</td></tr>)}</tbody>
            </table></div> : <div className="empty">Проверок пока нет. Начните с <a href="#/face">проверки лица</a> или <a href="#/voice">звонка</a>.</div>}
          </Card>
          <Card title="Модели и их состояние" sub="Ничего не имитируется: если модели нет, проверка честно сообщает об этом">
            <ModelList models={d.models} />
          </Card>
        </div>
        <div style={{ height: 16 }} />
        <Card title="Как устроена проверка">
          <div className="grid g3 small" style={{ color: "var(--text-2)" }}>
            <div><div className="section-title">1. Лицо</div>Детектор находит лицо и 5 точек. Проверка живости просит повернуть голову в случайном порядке: фото и записанное видео так не смогут. Сравнение с зарегистрированным лицом — только при согласии.</div>
            <div><div className="section-title">2. Голос и звонок</div>Запись переводится в текст на этом компьютере. Текст проверяется на тактики мошенников: просят код, «безопасный счёт», давят срочностью. По звучанию голоса человека мошенником не называем.</div>
            <div><div className="section-title">3. Общая оценка</div>Результаты лица, звонка и модели переводов объединяются прозрачными правилами. Если два независимых сигнала показывают риск — уровень повышается до высокого.</div>
          </div>
        </Card>
      </>;
    }}</Async>
  </>;
}

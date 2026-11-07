import { estCuotas, type EstadoCuota } from "../../api";
import Estado from "../admin/Estado";
import { useCarga } from "../admin/useCarga";
import { bs, fecha, nombreMes } from "../admin/util";

type Props = { onExpira: () => void };

const ESTADO: Record<EstadoCuota, { texto: string; clase: string }> = {
  PENDIENTE: { texto: "Pendiente", clase: "pill warn" },
  VENCIDA: { texto: "Vencida", clase: "pill bad" },
  PAGADA: { texto: "Pagada", clase: "pill ok" },
  ANULADA: { texto: "Anulada", clase: "pill off" },
};

export default function PagosEst({ onExpira }: Props) {
  const { datos, error, cargando, recargar } = useCarga(estCuotas, onExpira);

  if (!datos) return <Estado cargando={cargando} error={error} onReintentar={recargar} />;

  const porPagar = datos.filter((c) => c.estado === "PENDIENTE" || c.estado === "VENCIDA");
  const total = porPagar.reduce((suma, c) => suma + c.monto, 0);

  return (
    <>
      <p className="notice info">
        Para pagar una cuota, escanea el QR de la academia y presenta tu comprobante en secretaría. Cuando lo verifiquen,
        la cuota aparecerá como pagada.
      </p>

      <section className="panel-bloque" style={{ marginTop: 24 }}>
        <h2>Resumen</h2>
        <dl className="cifras">
          <div><dt>Cuotas por pagar</dt><dd>{porPagar.length}</dd></div>
          <div><dt>Total por pagar</dt><dd>{bs(total)}</dd></div>
        </dl>
      </section>

      <section className="panel-bloque">
        <h2>Todas mis cuotas</h2>
        <div className="tabla-scroll">
          <table className="tabla">
            <thead>
              <tr>
                <th>Cuota</th>
                <th>Monto</th>
                <th>Vence</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {datos.map((c) => (
                <tr key={c.idCuota} className={c.estado === "ANULADA" ? "fila-inactiva" : ""}>
                  <td>
                    <strong>{nombreMes(c.mes)} {c.anio}</strong>
                    <div className="sub">{c.curso}</div>
                  </td>
                  <td>{bs(c.monto)}</td>
                  <td>{fecha(`${c.fechaVencimiento}T00:00:00`)}</td>
                  <td>
                    <span className={ESTADO[c.estado].clase}>{ESTADO[c.estado].texto}</span>
                    {c.comprobanteEnRevision && <div className="sub">Comprobante en revisión</div>}
                  </td>
                </tr>
              ))}
              {datos.length === 0 && (
                <tr>
                  <td colSpan={4} className="vacio">
                    Todavía no tienes cuotas registradas.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

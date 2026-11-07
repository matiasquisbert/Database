import { adminCuotasVencidas, adminPagosPendientes, type TipoUsuario } from "../../api";
import Estado from "./Estado";
import { useCarga } from "./useCarga";
import { bs, fecha, fechaHora, nombreMes } from "./util";

type Props = { onExpira: () => void; tipoUsuario: TipoUsuario };

const MS_DIA = 86_400_000;

export default function PagosAdmin({ onExpira }: Props) {
  const pagos = useCarga(adminPagosPendientes, onExpira);
  const cuotas = useCarga(adminCuotasVencidas, onExpira);
  const hoy = Date.now();

  return (
    <>
      <section className="panel-bloque">
        <h2>Comprobantes por verificar</h2>
        <Estado cargando={!pagos.datos && pagos.cargando} error={pagos.error} onReintentar={pagos.recargar} />
        {pagos.datos && (
          <div className="tabla-scroll">
            <table className="tabla">
              <thead>
                <tr>
                  <th>Recibido</th>
                  <th>Estudiante</th>
                  <th>Concepto</th>
                  <th>Monto</th>
                  <th>Método</th>
                  <th>N.º de comprobante</th>
                </tr>
              </thead>
              <tbody>
                {pagos.datos.map((p) => (
                  <tr key={p.idTransaccion}>
                    <td>{fechaHora(p.fecha)}</td>
                    <td>
                      <strong>{p.estudiante}</strong>
                      <div className="sub">{p.curso}</div>
                    </td>
                    <td>{p.concepto ?? "—"}</td>
                    <td>{bs(p.monto)}</td>
                    <td>{p.metodoPago}</td>
                    <td>{p.nroComprobante ?? "—"}</td>
                  </tr>
                ))}
                {pagos.datos.length === 0 && (
                  <tr>
                    <td colSpan={6} className="vacio">
                      No hay comprobantes esperando verificación.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="panel-bloque">
        <h2>Cuotas vencidas</h2>
        <Estado cargando={!cuotas.datos && cuotas.cargando} error={cuotas.error} onReintentar={cuotas.recargar} />
        {cuotas.datos && (
          <div className="tabla-scroll">
            <table className="tabla">
              <thead>
                <tr>
                  <th>Estudiante</th>
                  <th>Cuota</th>
                  <th>Monto</th>
                  <th>Venció</th>
                </tr>
              </thead>
              <tbody>
                {cuotas.datos.map((c) => {
                  const dias = Math.max(0, Math.floor((hoy - new Date(`${c.fechaVencimiento}T00:00:00`).getTime()) / MS_DIA));
                  return (
                    <tr key={c.idCuota}>
                      <td>
                        <strong>{c.estudiante}</strong>
                        <div className="sub">{c.telefono ?? "Sin teléfono"}</div>
                      </td>
                      <td>
                        {nombreMes(c.mes)} {c.anio}
                        <div className="sub">{c.curso}</div>
                      </td>
                      <td>{bs(c.monto)}</td>
                      <td>
                        {fecha(`${c.fechaVencimiento}T00:00:00`)}
                        <div className="sub alerta">{dias === 1 ? "hace 1 día" : `hace ${dias} días`}</div>
                      </td>
                    </tr>
                  );
                })}
                {cuotas.datos.length === 0 && (
                  <tr>
                    <td colSpan={4} className="vacio">
                      Ningún estudiante tiene cuotas vencidas.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

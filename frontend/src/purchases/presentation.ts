import type { Order, Purchase } from './types';

export const purchaseSteps = ['Solicitud', 'Presupuesto', 'Pago', 'Preparación', 'Recepción'];
export function purchaseStep(status: string): number {
  if (status === 'ROUTING' || status === 'UNAVAILABLE') return 0;
  if (status === 'QUOTED') return 1;
  if (status === 'ACCEPTED' || status === 'PARTIALLY_PAID') return 2;
  if (status === 'PAID' || status === 'FULFILLING') return 3;
  if (status === 'COMPLETED') return 4;
  return -1;
}
export function pharmacyNextTask(order: Order, purchase: Purchase): string {
  if (order.cancellationPending)
    return order.financialControls?.some(
      (control) => control.operation === 'CANCEL' && control.valid,
    )
      ? 'Confirmar el cierre de la cancelación autorizada.'
      : 'Dos farmacéuticos deben revisar la cancelación de alto valor. La preparación está detenida.';
  if (order.replacementReviewPending || purchase.replacementReviewPending)
    return 'Revisar la documentación de reposición desde el pedido original antes de preparar.';
  if (order.fulfillment?.status === 'RETURN_REQUIRED')
    return 'Recibir físicamente el paquete retornado y registrar su custodia.';
  if (order.fulfillment?.status === 'RETURNED')
    return 'Acordar y documentar la resolución: reintegro, único reintento o cierre.';
  if (order.refunds.some((refund) => refund.status !== 'VERIFIED'))
    return order.highValueRequired &&
      !order.financialControls?.some((control) => control.operation === 'REFUND' && control.valid)
      ? 'Dos farmacéuticos deben comprobar el reintegro de alto valor antes de confirmarlo.'
      : order.invoiceDocumentId &&
          !order.fiscalAdjustments?.some((adjustment) => adjustment.kind === 'CREDIT')
        ? 'Registrar la nota de crédito correspondiente y realizar el reintegro.'
        : 'Comprobar y registrar el reintegro pendiente.';
  if (order.fulfillment?.status === 'INCIDENT')
    return 'Resolver la incidencia de este pedido con el comprador.';
  if (order.invoiceIssue?.active)
    return 'Resolver la emisión externa de la factura y adjuntar el documento válido. Administración recibió un aviso prioritario.';
  if (
    order.authorization &&
    !['APPROVED', 'ACTIVE'].includes(order.authorization.status) &&
    !['CANCELLED', 'RESOLVED'].includes(order.status)
  )
    return 'El farmacéutico debe resolver la autorización de compra y recepción.';
  if (['PROPOSED', 'REVIEW_PENDING'].includes(order.status))
    return order.highValueRequired && order.reviewCount === 1
      ? 'Falta la revisión de otro farmacéutico de esta sucursal.'
      : 'Revisar identidad, documentos, stock físico y propuesta.';
  if (order.status === 'QUOTED') return 'Esperando la decisión del comprador sobre esta propuesta.';
  if (['ACCEPTED', 'PAYMENT_PENDING'].includes(order.status))
    return order.paymentSubmission
      ? 'Comprobar externamente el pago informado por el comprador.'
      : 'Esperando el pago directo del comprador.';
  if (order.status === 'PAID')
    return !purchase.allOrdersPaid
      ? 'Esperando que las otras farmacias verifiquen sus pagos.'
      : !order.invoiceDocumentId
        ? 'Adjuntar la factura y comenzar la preparación.'
        : 'Comenzar la preparación de los medicamentos.';
  if (order.status === 'PREPARING') return 'Verificar el paquete y registrar la dispensación.';
  if (order.status === 'DISPENSED' && order.fulfillment?.status === 'ASSIGNED')
    return 'Esperar la aceptación del cadete propio asignado.';
  if (order.status === 'DISPENSED' && order.fulfillment?.assignmentMessage)
    return (
      order.fulfillment.assignmentMessage + '. Resolver disponibilidad o asignar un cadete apto.'
    );
  if (order.status === 'DISPENSED')
    return purchase.mode === 'PICKUP'
      ? 'Verificar al retirante y confirmar el retiro con su código.'
      : order.fulfillment?.status === 'READY'
        ? 'Asignar un cadete propio para entregar este paquete.'
        : 'Seguir el traslado del paquete y la recepción.';
  return 'Consultá las constancias y el cierre de este pedido.';
}

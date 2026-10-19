import { describe, expect, it } from 'vitest';
import { pharmacyNextTask, purchaseStep } from './presentation';
import type { Order, Purchase } from './types';

describe('orientación de la compra', () => {
  it('no muestra una compra cancelada como entrega completada', () => {
    expect(purchaseStep('CANCELLED')).toBe(-1);
    expect(purchaseStep('COMPLETED')).toBe(4);
    expect(purchaseStep('PARTIALLY_PAID')).toBe(2);
  });
  it('explica el bloqueo real de una compra dividida antes de preparar', () => {
    const order = { status: 'PAID', refunds: [], reviewCount: 1 } as unknown as Order;
    expect(pharmacyNextTask(order, { allOrdersPaid: false } as Purchase)).toContain(
      'otras farmacias',
    );
    expect(pharmacyNextTask(order, { allOrdersPaid: true } as Purchase)).toContain('factura');
  });
  it('prioriza el retorno físico antes de pedir un reintento', () => {
    const order = {
      status: 'DISPATCHED',
      refunds: [],
      fulfillment: { status: 'RETURN_REQUIRED' },
    } as unknown as Order;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('físicamente');
  });
  it('mantiene detenida la reposición hasta revisar documentos nuevos y prioriza su cancelación', () => {
    const order = {
      status: 'PAID',
      refunds: [],
      replacementReviewPending: true,
    } as unknown as Order;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('pedido original');
    order.cancellationPending = true;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('detenida');
  });
  it('muestra la cancelación detenida hasta que existe aprobación vigente', () => {
    const order = {
      status: 'PREPARING',
      refunds: [],
      cancellationPending: true,
      financialControls: [{ operation: 'CANCEL', valid: false }],
    } as unknown as Order;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('detenida');
    order.financialControls![0]!.valid = true;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('Confirmar el cierre');
  });
  it('no confunde solicitar una devolución con aprobarla o realizarla', () => {
    const order = {
      status: 'CANCELLED',
      highValueRequired: true,
      refunds: [{ status: 'REQUESTED' }],
      financialControls: [],
    } as unknown as Order;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('Dos farmacéuticos');
    order.financialControls = [
      { operation: 'REFUND', valid: true } as NonNullable<Order['financialControls']>[number],
    ];
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('registrar el reintegro pendiente');
  });
  it('indica la resolución de una emisión de factura fallida antes de dispensar', () => {
    const order = {
      status: 'PREPARING',
      refunds: [],
      invoiceIssue: { active: true },
    } as unknown as Order;
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('emisión externa');
    expect(pharmacyNextTask(order, {} as Purchase)).toContain('aviso prioritario');
  });
});

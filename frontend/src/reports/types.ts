export type SalesPeriod = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';

export type SalesTotals = {
  currency: string;
  gross: string;
  refunds: string;
  net: string;
  paidOrders: number;
  paymentCount: number;
  refundCount: number;
};

export type SalesBranch = {
  id: string;
  chainId: string | null;
  chainName: string | null;
  name: string;
  address: string;
};

export type SalesMovement = {
  id: string;
  kind: 'PAYMENT' | 'REFUND';
  orderId: string;
  purchaseId: string;
  branchId: string;
  branchName: string;
  chainName: string | null;
  amount: string;
  currency: string;
  verifiedAt: string;
  reference: string | null;
};

export type SalesReport = {
  period: SalesPeriod;
  date: string;
  from: string;
  toExclusive: string;
  timeZone: string;
  branches: SalesBranch[];
  totals: SalesTotals[];
  buckets: {
    from: string;
    toExclusive: string;
    label: string;
    totals: SalesTotals[];
  }[];
  byBranch: {
    branchId: string;
    branchName: string;
    chainName: string | null;
    totals: SalesTotals[];
  }[];
  movements: SalesMovement[];
  movementCount: number;
  movementsTruncated: boolean;
};

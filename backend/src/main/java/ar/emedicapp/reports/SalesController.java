package ar.emedicapp.reports;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
public class SalesController {
    private final SalesService sales;

    public SalesController(SalesService sales) {
        this.sales = sales;
    }

    @GetMapping("/api/reports/sales")
    public SalesDtos.Report report(
            @RequestParam(defaultValue = "MONTH") SalesDtos.Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @RequestParam(required = false) UUID branchId) {
        return sales.report(period, date, branchId);
    }
}

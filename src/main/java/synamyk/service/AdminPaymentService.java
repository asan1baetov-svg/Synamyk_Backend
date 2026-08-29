package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.admin.AdminPaymentResponse;
import synamyk.entities.Payment;
import synamyk.entities.TestSession;
import synamyk.entities.User;
import synamyk.repo.PaymentRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPaymentService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final PaymentRepository paymentRepository;
    private final MinioService minioService;

    public Page<AdminPaymentResponse> listPayments(
            int page, int size,
            String search,
            Payment.PaymentStatus status,
            LocalDateTime dateFrom,
            LocalDateTime dateTo) {

        Page<Payment> payments = paymentRepository.findAllByFilters(
                status, dateFrom, dateTo,
                (search != null && !search.isBlank()) ? search.trim() : null,
                PageRequest.of(page, size));

        return payments.map(this::toResponse);
    }

    @Transactional
    public AdminPaymentResponse updateStatus(Long id, Payment.PaymentStatus status) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Платёж не найден: " + id));
        payment.setStatus(status);
        if (status == Payment.PaymentStatus.COMPLETED && payment.getPaidAt() == null) {
            payment.setPaidAt(java.time.LocalDateTime.now());
        }
        paymentRepository.save(payment);
        log.info("Admin updated payment id={} status -> {}", id, status);
        return toResponse(payment);
    }

    @Transactional
    public void deletePayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Платёж не найден: " + id));
        paymentRepository.delete(payment);
        log.info("Admin deleted payment id={}", id);
    }

    public AdminPaymentResponse getPayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Платёж не найден: " + id));
        return toResponse(payment);
    }

    public byte[] exportCsv(
            String search,
            Payment.PaymentStatus status,
            LocalDateTime dateFrom,
            LocalDateTime dateTo) {

        List<Payment> payments = paymentRepository.findAllByFiltersUnpaged(
                status, dateFrom, dateTo,
                (search != null && !search.isBlank()) ? search.trim() : null);

        StringBuilder sb = new StringBuilder();
        sb.append("ID транзакции,Пользователь,Телефон,Сумма (сом),Способ оплаты,Дата,Статус,Балл,Тест\n");
        for (Payment p : payments) {
            AdminPaymentResponse r = toResponse(p);
            sb.append(csv(r.getTransactionId())).append(',')
              .append(csv(r.getUser().getFullName())).append(',')
              .append(csv(r.getUser().getPhone())).append(',')
              .append(r.getAmount()).append(',')
              .append(csv(r.getPaymentMethod())).append(',')
              .append(r.getDate() != null ? r.getDate().format(DATE_FMT) : "").append(',')
              .append(csv(r.getStatus())).append(',')
              .append(r.getEarnedPoints()).append(',')
              .append(csv(r.getTestTitle())).append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] exportExcel(
            String search,
            Payment.PaymentStatus status,
            LocalDateTime dateFrom,
            LocalDateTime dateTo) throws IOException {

        List<Payment> payments = paymentRepository.findAllByFiltersUnpaged(
                status, dateFrom, dateTo,
                (search != null && !search.isBlank()) ? search.trim() : null);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Платежи Finik");

            String[] headers = {"ID транзакции", "Пользователь", "Телефон",
                    "Сумма (сом)", "Способ оплаты", "Дата", "Статус", "Балл", "Тест"};

            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (Payment p : payments) {
                AdminPaymentResponse r = toResponse(p);
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(r.getTransactionId() != null ? r.getTransactionId() : String.valueOf(r.getId()));
                row.createCell(1).setCellValue(r.getUser().getFullName());
                row.createCell(2).setCellValue(r.getUser().getPhone());
                row.createCell(3).setCellValue(r.getAmount().doubleValue());
                row.createCell(4).setCellValue(r.getPaymentMethod());
                row.createCell(5).setCellValue(r.getDate() != null ? r.getDate().format(DATE_FMT) : "");
                row.createCell(6).setCellValue(r.getStatus());
                row.createCell(7).setCellValue(r.getEarnedPoints());
                row.createCell(8).setCellValue(r.getTestTitle());
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private AdminPaymentResponse toResponse(Payment p) {
        User u = p.getUser();
        String fullName = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();

        Integer points = paymentRepository.sumEarnedPointsByUserAndTest(
                u.getId(), p.getTest().getId(), TestSession.SessionStatus.COMPLETED);

        return AdminPaymentResponse.builder()
                .id(p.getId())
                .transactionId(p.getTransactionId() != null ? p.getTransactionId() : p.getPaymentId().toString())
                .user(AdminPaymentResponse.UserInfo.builder()
                        .id(u.getId())
                        .fullName(fullName.isEmpty() ? u.getPhone() : fullName)
                        .phone(u.getPhone())
                        .avatarUrl(minioService.presign(u.getAvatarUrl()))
                        .build())
                .amount(p.getAmount())
                .paymentMethod("Finik")
                .status(p.getStatus().name())
                .date(p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt())
                .earnedPoints(points != null ? points : 0)
                .testTitle(p.getTest().getTitle())
                .subTestId(p.getSubTest() != null ? p.getSubTest().getId() : null)
                .subTestTitle(p.getSubTest() != null ? p.getSubTest().getTitle() : null)
                .build();
    }

    private String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

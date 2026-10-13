// Projections below intentionally model the service boundaries in
// exam-grading-documents/source/04-data-model-final.md.
const ids = {
  examKtct: '8b9dd7f5-ea7d-4b5b-a498-000000000001', examPldc: '8b9dd7f5-ea7d-4b5b-a498-000000000002', examThml: '8b9dd7f5-ea7d-4b5b-a498-000000000003',
  template: '0a809b8a-4b10-4b64-9305-000000000001', grader: '36586c2c-735e-42bd-97a3-000000000001',
}

export const mockData = {
  paperTemplates: [{ templateId: ids.template, name: 'KTCT-A4-v2', pageWidth: 210, pageHeight: 297, maskX: 0.08, maskY: 0.04, maskWidth: 0.28, maskHeight: 0.06, pseudonymPrintRegion: { page: 1, x: 0.72, y: 0.04 }, active: true }],
  exams: [
    { examId: ids.examKtct, examCode: 'KTCT-HK1-2026', name: 'Thi kết thúc học phần Kinh tế chính trị', subject: 'Kinh tế chính trị Mác – Lênin', examTime: '2026-12-15T01:00:00Z', gradeLevel: 'K66 · Lớp 01–08', scale: 10, crossGradingEnabled: true, graderCount: 2, differenceThreshold: 1, templateId: ids.template, status: 'ACTIVE' },
    { examId: ids.examPldc, examCode: 'PLDC-HK1-2026', name: 'Thi kết thúc học phần Pháp luật đại cương', subject: 'Pháp luật đại cương', examTime: '2026-12-17T06:30:00Z', gradeLevel: 'K66 · Lớp 01–06', scale: 10, crossGradingEnabled: true, graderCount: 2, differenceThreshold: 1, templateId: ids.template, status: 'ACTIVE' },
    { examId: ids.examThml, examCode: 'THML-HK1-2026', name: 'Kiểm tra Triết học Mác – Lênin', subject: 'Triết học Mác – Lênin', examTime: '2026-12-21T01:00:00Z', gradeLevel: 'K66 · Lớp 01–10', scale: 10, crossGradingEnabled: false, graderCount: 1, differenceThreshold: 0, templateId: ids.template, status: 'DRAFT' },
  ],
  candidates: [
    { candidateId: '1af04e06-e021-4fde-9d43-000000000001', examId: ids.examKtct, candidateNumber: 'SV2200248', fullName: 'Nguyễn Minh An', className: 'K66A1' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000002', examId: ids.examKtct, candidateNumber: 'SV2200312', fullName: 'Trần Gia Bảo', className: 'K66A2' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000003', examId: ids.examKtct, candidateNumber: 'SV2200417', fullName: 'Lê Khánh Linh', className: 'K66A3' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000004', examId: ids.examKtct, candidateNumber: 'SV2200556', fullName: 'Hoàng Quốc Việt', className: 'K66A4' },
    // PLDC has a roster but no answer scripts uploaded yet. It is used to
    // demonstrate the "chưa upload bài thi" state in the office dashboard.
    { candidateId: '1af04e06-e021-4fde-9d43-000000000005', examId: ids.examPldc, candidateNumber: 'SV2200601', fullName: 'Phạm Thu Hà', className: 'K66B1' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000006', examId: ids.examPldc, candidateNumber: 'SV2200638', fullName: 'Đỗ Thành Nam', className: 'K66B2' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000007', examId: ids.examPldc, candidateNumber: 'SV2200714', fullName: 'Bùi Ngọc Mai', className: 'K66B3' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000008', examId: ids.examPldc, candidateNumber: 'SV2200789', fullName: 'Vũ Đức Anh', className: 'K66B4' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000009', examId: ids.examThml, candidateNumber: 'SV2200816', fullName: 'Ngô Phương Thảo', className: 'K66C1' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000010', examId: ids.examThml, candidateNumber: 'SV2200892', fullName: 'Dương Gia Huy', className: 'K66C2' },
    { candidateId: '1af04e06-e021-4fde-9d43-000000000011', examId: ids.examThml, candidateNumber: 'SV2200935', fullName: 'Lý Thanh Trúc', className: 'K66C3' },
  ],
  submissions: [
    { submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000001', examId: ids.examKtct, templateId: ids.template, status: 'GRADING', pageCount: 8, declaredFileSizeBytes: 1280000, fileSizeBytes: 1279321, progress: 65, updatedAt: '2026-12-15T02:18:00Z' },
    { submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000002', examId: ids.examKtct, templateId: ids.template, status: 'READY_FOR_GRADING', pageCount: 7, declaredFileSizeBytes: 1140000, fileSizeBytes: 1136002, progress: 100, updatedAt: '2026-12-15T02:24:00Z' },
    { submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000003', examId: ids.examKtct, templateId: ids.template, status: 'REVIEW_REQUIRED', pageCount: 9, declaredFileSizeBytes: 1430000, fileSizeBytes: 1424800, progress: 100, updatedAt: '2026-12-15T02:31:00Z' },
    { submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000004', examId: ids.examKtct, templateId: ids.template, status: 'COMPLETED', pageCount: 6, declaredFileSizeBytes: 1040000, fileSizeBytes: 1037001, progress: 100, updatedAt: '2026-12-15T02:42:00Z' },
  ],
  pseudonyms: [
    { pseudonymId: '7053112b-449c-46cc-a25c-000000000001', examId: ids.examKtct, submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000001', candidateId: '1af04e06-e021-4fde-9d43-000000000001', value: 'P-9K4M', active: true },
    { pseudonymId: '7053112b-449c-46cc-a25c-000000000002', examId: ids.examKtct, submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000002', candidateId: '1af04e06-e021-4fde-9d43-000000000002', value: 'P-2H8Q', active: true },
    { pseudonymId: '7053112b-449c-46cc-a25c-000000000003', examId: ids.examKtct, submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000003', candidateId: '1af04e06-e021-4fde-9d43-000000000003', value: 'P-7R1X', active: true },
    { pseudonymId: '7053112b-449c-46cc-a25c-000000000004', examId: ids.examKtct, submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000004', candidateId: '1af04e06-e021-4fde-9d43-000000000004', value: 'P-5D6N', active: true },
  ],
  questions: [{ questionId: 'c1f3e287-7da0-4e3d-850a-000000000001', examId: ids.examKtct, orderNo: 1, content: 'Câu 1', maxScore: 2, required: true }, { questionId: 'c1f3e287-7da0-4e3d-850a-000000000002', examId: ids.examKtct, orderNo: 2, content: 'Câu 2', maxScore: 4, required: true }, { questionId: 'c1f3e287-7da0-4e3d-850a-000000000003', examId: ids.examKtct, orderNo: 3, content: 'Câu 3', maxScore: 4, required: true }],
  criteria: [{ criterionId: 'a0edac74-0716-4a26-b7be-000000000001', questionId: 'c1f3e287-7da0-4e3d-850a-000000000001', label: 'Nội dung kiến thức', description: 'Xác định đúng vấn đề nghị luận', maxScore: 1.25, orderNo: 1 }, { criterionId: 'a0edac74-0716-4a26-b7be-000000000002', questionId: 'c1f3e287-7da0-4e3d-850a-000000000001', label: 'Kỹ năng phân tích và lập luận', description: 'Lập luận, dẫn chứng', maxScore: 0.75, orderNo: 2 }, { criterionId: 'a0edac74-0716-4a26-b7be-000000000003', questionId: 'c1f3e287-7da0-4e3d-850a-000000000002', label: 'Nội dung kiến thức', description: 'Các yếu tố hình thức và đặc trưng', maxScore: 4, orderNo: 1 }, { criterionId: 'a0edac74-0716-4a26-b7be-000000000004', questionId: 'c1f3e287-7da0-4e3d-850a-000000000003', label: 'Hình thức và ngôn ngữ', description: 'Trình bày, diễn đạt', maxScore: 4, orderNo: 1 }],
  rubrics: [{ rubricId: '5ebcfdd3-9c9b-4f82-8e38-000000000001', examId: ids.examKtct, version: 1, status: 'ACTIVE', source: 'DOCX', confirmedBy: 'b9a3f02b-3f31-4d4e-82d2-000000000001', confirmedAt: '2026-12-10T03:00:00Z', contentJson: { rows: [], sourceFileName: 'rubric-ktct-v1.docx' } }],
  assignments: [{ assignmentId: '86ad1014-02ec-48c8-bfef-000000000001', submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000001', examId: ids.examKtct, graderId: ids.grader, assignmentType: 'BULK', assignedAt: '2026-12-15T02:00:00Z', status: 'IN_PROGRESS' }],
  attempts: [{ attemptId: 'db33b748-3047-461a-8278-000000000001', assignmentId: '86ad1014-02ec-48c8-bfef-000000000001', submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000001', graderId: ids.grader, attemptNo: 1, isRegrade: false, status: 'DRAFT', totalScore: null, confirmedAt: null }],
  questionScores: [
    { questionScoreId: '7fdc7fbf-41a1-43cd-964a-000000000001', attemptId: 'db33b748-3047-461a-8278-000000000001', questionId: 'c1f3e287-7da0-4e3d-850a-000000000001', score: 1.5, note: 'Đủ ý chính.', needsReview: false },
    { questionScoreId: '7fdc7fbf-41a1-43cd-964a-000000000002', attemptId: 'db33b748-3047-461a-8278-000000000001', questionId: 'c1f3e287-7da0-4e3d-850a-000000000002', score: 3.25, note: null, needsReview: false },
    { questionScoreId: '7fdc7fbf-41a1-43cd-964a-000000000003', attemptId: 'db33b748-3047-461a-8278-000000000001', questionId: 'c1f3e287-7da0-4e3d-850a-000000000003', score: 3, note: 'Cần bổ sung ví dụ.', needsReview: false },
  ],
  reviews: [{ reviewId: '34d7dc6e-3a37-4cf7-a7f7-000000000001', submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000003', examId: ids.examKtct, reasonType: 'SCORE_DIFFERENCE', reason: 'Chênh lệch 2.00 điểm, vượt ngưỡng 1.00.', status: 'RESOLVED', openedAt: '2026-12-15T03:10:00Z', resolvedAt: '2026-12-15T05:00:00Z', resolutionType: 'COUNCIL_DECISION' }],
  councilDecisions: [{ decisionId: 'b7ab28a1-84fb-43d2-8878-000000000001', reviewId: '34d7dc6e-3a37-4cf7-a7f7-000000000001', finalScore: 8.5, decisionSource: 'COUNCIL', reason: 'Đối chiếu bài làm và rubric.', decidedBy: '6f333c73-8db1-4f9d-b2a8-000000000001', decidedAt: '2026-12-15T05:00:00Z' }],
  finalResults: [{ resultId: '4434176a-6e59-4b9d-8d9c-000000000001', submissionId: 'c08bc820-66a5-4bd3-8e1a-000000000004', examId: ids.examKtct, candidateId: '1af04e06-e021-4fde-9d43-000000000004', candidateNumber: 'SV2200556', candidateName: 'Hoàng Quốc Việt', examName: 'Thi kết thúc học phần Kinh tế chính trị', subject: 'Kinh tế chính trị Mác – Lênin', finalScore: 7.75, decisionSource: 'CROSS_GRADING', finalizedAt: '2026-12-15T07:00:00Z', retentionDeadline: '2027-12-15T07:00:00Z', purgeStatus: 'PENDING' }],
}

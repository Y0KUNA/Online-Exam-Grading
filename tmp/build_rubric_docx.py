from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.enum.section import WD_ORIENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

OUT = r"output/docx/cham-rubric-van.docx"

def set_cell_shading(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn('w:shd'))
    if shd is None:
        shd = OxmlElement('w:shd')
        tc_pr.append(shd)
    shd.set(qn('w:fill'), fill)

def set_cell_margins(cell, top=80, start=80, bottom=80, end=80):
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    tcMar = tcPr.first_child_found_in('w:tcMar')
    if tcMar is None:
        tcMar = OxmlElement('w:tcMar')
        tcPr.append(tcMar)
    for m, v in [('top', top), ('start', start), ('bottom', bottom), ('end', end)]:
        node = tcMar.find(qn(f'w:{m}'))
        if node is None:
            node = OxmlElement(f'w:{m}')
            tcMar.append(node)
        node.set(qn('w:w'), str(v)); node.set(qn('w:type'), 'dxa')

def set_repeat_table_header(row):
    trPr = row._tr.get_or_add_trPr()
    el = OxmlElement('w:tblHeader')
    el.set(qn('w:val'), 'true')
    trPr.append(el)

def set_no_split(row):
    trPr = row._tr.get_or_add_trPr()
    el = OxmlElement('w:cantSplit')
    trPr.append(el)

def put(cell, text, bold=False, align=WD_ALIGN_PARAGRAPH.LEFT, size=8.2):
    cell.text = ''
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
    set_cell_margins(cell)
    lines = str(text).split('\n')
    for i, line in enumerate(lines):
        p = cell.paragraphs[0] if i == 0 else cell.add_paragraph()
        p.alignment = align
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.line_spacing = 1.0
        r = p.add_run(line)
        r.bold = bold
        r.font.name = 'Times New Roman'
        r._element.rPr.rFonts.set(qn('w:eastAsia'), 'Times New Roman')
        r.font.size = Pt(size)

def base_table(doc):
    table = doc.add_table(rows=1, cols=7)
    table.style = 'Table Grid'
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    widths = [1.45, 2.35, 2.7, 6.7, 3.35, 3.35, 3.35]
    for cell, width in zip(table.rows[0].cells, widths):
        cell.width = Cm(width)
    headers = ['Câu', 'Tiêu chí', 'Thành phần\nnội dung', 'Mức 4', 'Mức 3', 'Mức 2', 'Mức 1']
    for cell, value in zip(table.rows[0].cells, headers):
        put(cell, value, True, WD_ALIGN_PARAGRAPH.CENTER, 8.7)
        set_cell_shading(cell, 'D9E2F3')
    set_repeat_table_header(table.rows[0])
    return table

def row(table, values, score_ranges=None, shade=False):
    cells = table.add_row().cells
    set_no_split(table.rows[-1])
    for i, (cell, val) in enumerate(zip(cells, values)):
        txt = val
        if score_ranges and i >= 3 and score_ranges[i-3]:
            txt = score_ranges[i-3] + '\n' + val
        put(cell, txt, i in (0,1,2), WD_ALIGN_PARAGRAPH.CENTER if i == 0 else WD_ALIGN_PARAGRAPH.LEFT)
        if shade:
            set_cell_shading(cell, 'E7E6E6')
    return cells

def merge_vertical(table, col, start, end):
    table.cell(start, col).merge(table.cell(end, col))

doc = Document()
sec = doc.sections[0]
sec.orientation = WD_ORIENT.LANDSCAPE
sec.page_width, sec.page_height = Cm(29.7), Cm(21.0)
sec.top_margin = Cm(0.65); sec.bottom_margin = Cm(0.65)
sec.left_margin = Cm(0.7); sec.right_margin = Cm(0.7)

styles = doc.styles
styles['Normal'].font.name = 'Times New Roman'
styles['Normal']._element.rPr.rFonts.set(qn('w:eastAsia'), 'Times New Roman')
styles['Normal'].font.size = Pt(9)

for text, size, bold, italic in [
    ('BỘ GIÁO DỤC VÀ ĐÀO TẠO', 11, True, False),
    ('RUBRIC CHẤM ĐIỂM PHẦN II VIẾT', 12, True, False),
    ('ĐỀ THI CHÍNH THỨC', 10, True, False),
    ('KỲ THI TỐT NGHIỆP TRUNG HỌC PHỔ THÔNG NĂM 2026', 11, True, False),
    ('Môn thi NGỮ VĂN', 11, True, False),
    ('Thời gian làm bài 120 phút không kể thời gian phát đề', 10, False, True),
]:
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(0)
    r = p.add_run(text); r.bold = bold; r.italic = italic; r.font.name = 'Times New Roman'; r.font.size = Pt(size)

p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.paragraph_format.space_before = Pt(3); p.paragraph_format.space_after = Pt(3)
r = p.add_run('RUBRIC CHẤM ĐIỂM PHẦN II VIẾT'); r.bold = True; r.font.size = Pt(14); r.font.name = 'Times New Roman'

t = base_table(doc)
row(t, ['Câu 1\n(2,0 điểm)', 'I. Nội dung kiến thức\n(1,25 điểm)', 'Xác định đúng vấn đề nghị luận\n(0,25 điểm)', 'Xác định đúng vấn đề nghị luận: Từ góc nhìn của người trẻ, đề xuất các giải pháp để Việt Nam có những nhà phát minh công nghệ mang tầm vóc thế giới.\n\n0,25 điểm', '', '', 'Không xác định được hoặc xác định sai vấn đề nghị luận.\n\n0,0 điểm'], ['(1,75 - 2,0 điểm)', '(1,25 - 1,5 điểm)', '(0,75 - 1,0 điểm)', '(0,0 - 0,5 điểm)'], True)
row(t, ['', '', 'Các giải pháp\n(1,0 điểm)', 'Nêu đủ 03 ý trong ý 1 (ý 1.1, 1.2, 1.3) và nêu được 01 ý trong ý 2 (ý 2.1, 2.2, 2.3) ở mục b. Yêu cầu cụ thể của Đáp án.\n\n1,0 điểm', 'Nêu được 02 ý trong ý 1 và nêu được 01 ý trong ý 2.\n\n0,75 điểm', 'Nêu được 01 ý trong ý 1 và nêu được 01 ý trong ý 2; hoặc nêu được 03 ý trong ý 1; hoặc nêu được 03 ý trong ý 2.\n\n0,5 điểm', 'Nêu được 01 ý trong ý 1 hoặc nêu được 01 ý trong ý 2; hoặc nêu được 02 ý trong ý 2.\n\n0,25 điểm'])
row(t, ['', 'II. Kỹ năng phân tích và lập luận\n(0,5 điểm)', '', 'Trình bày được quan điểm của bản thân.\nLựa chọn được thao tác nghị luận phù hợp.\nLập luận chặt chẽ, logic, có 01 bằng chứng thuyết phục.\n\n0,5 điểm', 'Đáp ứng được 01 trong 03 yêu cầu như mức 4.\n\n0,25 điểm', 'Không đáp ứng được yêu cầu nào như mức 4.\n\n0,0 điểm', ''])
row(t, ['', 'III. Hình thức và ngôn ngữ\n(0,25 điểm)', '', 'Bảo đảm cách thức trình bày đoạn văn.\nHành văn trong sáng, giàu cảm xúc, đúng ngữ pháp tiếng Việt, không có lỗi chính tả.\nTrình bày khoa học, rõ ràng, sạch đẹp.\nDung lượng: 200 ± 100 chữ.\n\n0,25 điểm', 'Thí sinh đáp ứng được 04 yêu cầu nhưng chưa đầy đủ như mức 4.', 'Thí sinh đáp ứng được 03 yêu cầu nhưng chưa đầy đủ như mức 4.', ''])
merge_vertical(t, 0, 1, 4)

doc.add_page_break()
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.paragraph_format.space_after = Pt(3)
r = p.add_run('RUBRIC CHẤM ĐIỂM CÂU 2'); r.bold = True; r.font.size = Pt(13); r.font.name = 'Times New Roman'
t = base_table(doc)
row(t, ['Câu 2\n(4,0 điểm)', 'I. Nội dung kiến thức\n(2,5 điểm)', 'b2.1. Thể thơ, đề tài, chủ thể trữ tình/nhân vật trữ tình, bố cục và mạch cảm xúc\n(0,25 điểm)', 'Nêu được chính xác các yếu tố thể thơ, đề tài, chủ thể trữ tình/nhân vật trữ tình, bố cục và mạch cảm xúc như Đáp án.\n\n0,25 điểm', 'Nêu được chính xác 03 trong 04 ý; hoặc nêu được 04 ý nhưng không đầy đủ.\n\n0,25 điểm', 'Nêu được chính xác 02 trong 04 ý; hoặc nêu được 03 ý nhưng không đầy đủ.\n\n0,25 điểm', 'Nêu được chính xác 01 trong 04 ý.\n\n0,0 điểm'], ['(3,0 - 4,0 điểm)', '(2,0 - 2,75 điểm)', '(0,75 - 1,75 điểm)', '(0,0 - 0,5 điểm)'], True)
row(t, ['', '', 'b2.2.1. Trước khi chú bé xuất hiện\n(0,75 điểm)', 'Nêu được đầy đủ các yếu tố hình thức như Đáp án.\nPhân tích được các yếu tố hình thức để rút ra nội dung như Đáp án.\n\n0,75 điểm', 'Nêu được chính xác 02 trong 03 ý hình thức. Phân tích được ý 4 và ý 5.1 hoặc ý 5.2.\n\n0,5 điểm', 'Nêu được chính xác 01 trong 03 ý hình thức. Phân tích được ý 4 hoặc ý 5.1 hoặc ý 5.2.\n\n0,25 điểm', 'Không nêu được ý nào.\n\n0,0 điểm'])
row(t, ['', '', 'b2.2.2. Khi chú bé xuất hiện\n(0,75 điểm)', 'Nêu được đầy đủ các yếu tố hình thức như Đáp án. Phân tích được các yếu tố hình thức để rút ra nội dung như Đáp án.\n\n0,75 điểm', 'Nêu được chính xác 02 trong 03 ý hình thức. Phân tích được ý 4, ý 5 và ý 6.1 hoặc ý 6.2.\n\n0,5 điểm', 'Nêu được chính xác 01 trong 03 ý hình thức. Phân tích được ý 4 hoặc ý 5 hoặc ý 6.1 hoặc ý 6.2.\n\n0,25 điểm', 'Không nêu được ý nào.\n\n0,0 điểm'])
row(t, ['', '', 'b2.3. Đánh giá được những đặc sắc nghệ thuật và nội dung của bài thơ\n(0,5 điểm)', 'Đánh giá được 03 trong 04 ý đặc sắc nghệ thuật như Đáp án. Đánh giá được nội dung bài thơ như Đáp án.\n\n0,5 điểm', 'Đánh giá được 02 trong 04 ý đặc sắc nghệ thuật. Đánh giá được nội dung.\n\n0,25 điểm', 'Đánh giá được 01 trong 04 ý đặc sắc nghệ thuật. Đánh giá được nội dung.\n\n0,25 điểm', 'Chưa đánh giá được ý nào.\n\n0,0 điểm'])
row(t, ['', '', 'b2.4. Liên hệ, mở rộng\n(0,25 điểm)', 'So sánh với các bài thơ/câu thơ khác để rút ra được những điểm tương đồng hoặc khác biệt một cách sâu sắc, thuyết phục. Rút ra được thông điệp/Bài học nhân sinh sâu sắc, có ý nghĩa.\n\n0,25 điểm', 'Triển khai được 01 trong 02 ý.', 'Triển khai được 01 ý nhưng còn chung chung.', 'Chưa triển khai được cả 02 ý.\n\n0,0 điểm'])
row(t, ['', 'II. Kỹ năng phân tích và lập luận\n(0,75 điểm)', '', 'Biết lựa chọn các chi tiết tiêu biểu miêu tả hình ảnh chiếc lá, chú bé và mối quan hệ giữa các chi tiết, hình ảnh làm căn cứ để rút ra nội dung bài thơ.\nSử dụng thành thạo các kĩ năng nghị luận để phân tích, đánh giá giá trị bài thơ.\nBiết liên hệ, kết nối với các văn bản khác và đời sống một cách thuyết phục.\n\n0,75 điểm', 'Biết lựa chọn các chi tiết tiêu biểu miêu tả hình ảnh chiếc lá, chú bé và mối quan hệ giữa các chi tiết, hình ảnh làm căn cứ để rút ra nội dung bài thơ. Sử dụng thành thạo các kĩ năng nghị luận để phân tích, đánh giá giá trị bài thơ.\n\n0,5 điểm', 'Biết lựa chọn các chi tiết miêu tả hình ảnh chiếc lá và chú bé làm căn cứ để rút ra nội dung bài thơ. Sử dụng chưa thành thạo các kĩ năng nghị luận để phân tích, đánh giá giá trị bài thơ.\n\n0,25 điểm', 'Chưa lựa chọn được các chi tiết miêu tả hình ảnh chiếc lá và chú bé. Chưa biết cách tổ chức lập luận.\n\n0,0 điểm'])
row(t, ['', 'III. Hình thức và ngôn ngữ\n(0,75 điểm)', '', 'Bố cục 3 phần rõ ràng (Mở bài - Thân bài - Kết bài).\nDung lượng: 600 ± 200 chữ.\nHành văn trong sáng, giàu cảm xúc, đúng ngữ pháp tiếng Việt, không có lỗi chính tả.\nTrình bày khoa học, rõ ràng, sạch đẹp.\n\n0,75 điểm', 'Đáp ứng được ý 1, ý 2, ý 3.\n\n0,5 điểm', 'Đáp ứng được ý 1, ý 2.\n\n0,25 điểm', 'Đáp ứng được ý 1 hoặc chỉ viết được mở bài mà không có thân bài, kết bài.'])
merge_vertical(t, 0, 1, 6)

p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.paragraph_format.space_before = Pt(3)
r = p.add_run('HẾT'); r.bold = True; r.font.name = 'Times New Roman'; r.font.size = Pt(11)
doc.save(OUT)
print(OUT)

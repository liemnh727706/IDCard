@echo off
python -m pip install -r requirements.txt
python -m PyInstaller --noconfirm --onefile --windowed --name EmvCardReaderWin ^
  --collect-submodules winrt app.py
echo Xong: dist\EmvCardReaderWin.exe

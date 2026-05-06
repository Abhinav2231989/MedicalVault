// Reports and Status Features JavaScript
// Android WebView Compatible Version

console.log('📎 Loading reports-status.js (Android Compatible)...');

// File handling for reports
let selectedFiles = [];

// Initialize when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initializeFileHandling);
} else {
  initializeFileHandling();
}

function initializeFileHandling() {
  console.log('✅ Initializing file handling for Android...');
  
  // Make sure file input is accessible
  const fileInput = document.getElementById('reports-input');
  if (fileInput) {
    console.log('✅ File input found');
    
    // Add change event listener
    fileInput.addEventListener('change', function(event) {
      handleFileSelect(event);
    });
    
    // Add click event to prevent default if needed
    fileInput.addEventListener('click', function(event) {
      console.log('📎 File input clicked');
      // Don't prevent default - let it open file picker
    });
  } else {
    console.warn('⚠️ File input not found');
  }
  
  // Override the button click to properly trigger file input
  setupFileButton();
}

function setupFileButton() {
  // Find the file upload button
  const uploadBtn = document.querySelector('.file-upload-btn');
  if (uploadBtn) {
    console.log('✅ File upload button found');
    
    uploadBtn.addEventListener('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      
      console.log('📎 Upload button clicked');
      
      const fileInput = document.getElementById('reports-input');
      if (fileInput) {
        // For Android WebView, we need to trigger click directly
        try {
          fileInput.click();
          console.log('✅ File input clicked programmatically');
        } catch (error) {
          console.error('❌ Error clicking file input:', error);
        }
      }
    });
  }
}

function handleFileSelect(event) {
  console.log('📎 File select triggered');
  console.log('Event:', event);
  console.log('Files:', event.target.files);
  
  const files = event.target.files;
  
  if (!files || files.length === 0) {
    console.log('No files selected');
    return;
  }
  
  console.log('📎 Processing', files.length, 'file(s)');
  
  // Convert FileList to Array
  const fileArray = Array.from(files);
  
  let processedCount = 0;
  
  fileArray.forEach((file, index) => {
    console.log('Processing file', index + 1, ':', file.name, 'Size:', file.size, 'Type:', file.type);
    
    // Check file size (max 5MB)
    if (file.size > 5 * 1024 * 1024) {
      console.warn('File too large:', file.name);
      if (typeof showWarningMessage === 'function') {
        showWarningMessage(`File ${file.name} is too large. Maximum size is 5MB.`);
      } else {
        alert(`File ${file.name} is too large. Maximum size is 5MB.`);
      }
      processedCount++;
      if (processedCount === fileArray.length) {
        event.target.value = '';
      }
      return;
    }
    
    // Read file as base64
    const reader = new FileReader();
    
    reader.onload = function(e) {
      console.log('✅ File loaded:', file.name);
      
      const fileData = {
        name: file.name,
        size: file.size,
        type: file.type || 'application/octet-stream',
        data: e.target.result
      };
      
      selectedFiles.push(fileData);
      console.log('✅ File added to selectedFiles. Total:', selectedFiles.length);
      
      processedCount++;
      
      // Update display after all files are processed
      if (processedCount === fileArray.length) {
        displayFileList();
        event.target.value = ''; // Clear input
        console.log('✅ All files processed');
      }
    };
    
    reader.onerror = function(error) {
      console.error('❌ Error reading file:', file.name, error);
      alert('Error reading file: ' + file.name);
      
      processedCount++;
      if (processedCount === fileArray.length) {
        event.target.value = '';
      }
    };
    
    // Start reading
    try {
      reader.readAsDataURL(file);
      console.log('📖 Reading file:', file.name);
    } catch (error) {
      console.error('❌ Error starting file read:', error);
      processedCount++;
    }
  });
}

function displayFileList() {
  const fileListDiv = document.getElementById('file-list');
  if (!fileListDiv) {
    console.warn('⚠️ file-list div not found');
    return;
  }
  
  console.log('📋 Displaying file list. Total files:', selectedFiles.length);
  
  if (selectedFiles.length === 0) {
    fileListDiv.innerHTML = '<div style="color: var(--text-dim); font-size: 0.8rem; margin-top: 0.5rem; padding: 0.5rem;">No files selected</div>';
    return;
  }
  
  const html = selectedFiles.map((file, index) => {
    const icon = file.type.startsWith('image/') ? '🖼️' : '📄';
    return `
      <div class="file-item" data-index="${index}">
        <div class="file-item-info">
          <span class="file-icon">${icon}</span>
          <div style="flex: 1; min-width: 0;">
            <div class="file-item-name" title="${file.name}">${file.name}</div>
            <div class="file-item-size">${formatFileSize(file.size)}</div>
          </div>
        </div>
        <button type="button" class="file-remove-btn" onclick="removeFile(${index})">Remove</button>
      </div>
    `;
  }).join('');
  
  fileListDiv.innerHTML = html;
  console.log('✅ File list displayed');
}

function removeFile(index) {
  console.log('🗑️ Removing file at index:', index);
  if (index >= 0 && index < selectedFiles.length) {
    const removed = selectedFiles.splice(index, 1);
    console.log('🗑️ Removed:', removed[0].name);
    console.log('Files remaining:', selectedFiles.length);
    displayFileList();
  }
}

function formatFileSize(bytes) {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
}

function viewReports(recordId) {
  console.log('👁️ Viewing reports for record:', recordId);
  
  // Get loadData function from parent scope
  if (typeof loadData !== 'function') {
    console.error('❌ loadData function not found');
    alert('Error: Cannot load reports');
    return;
  }
  
  loadData('patient-records').then(records => {
    const record = records.find(r => r.id === recordId);
    
    if (!record) {
      alert('Record not found');
      return;
    }
    
    if (!record.reports || record.reports.length === 0) {
      alert('No reports found for this patient.');
      return;
    }
    
    console.log('📄 Showing', record.reports.length, 'reports');
    
    // Create modal to view reports
    const modal = document.createElement('div');
    modal.className = 'reports-modal';
    modal.style.cssText = 'position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.8); z-index: 2000; display: flex; align-items: center; justify-content: center; padding: 1rem;';
    
    const modalContent = `
      <div style="background: var(--bg-card); padding: 2rem; border-radius: 12px; max-width: 600px; width: 100%; max-height: 80vh; overflow-y: auto; border: 1px solid var(--border);">
        <h3 style="color: var(--primary); margin-bottom: 1rem; font-family: 'Syne', sans-serif;">Reports for ${record.patientName}</h3>
        <div style="display: flex; flex-direction: column; gap: 1rem;">
          ${record.reports.map((file, idx) => {
            const icon = file.type.startsWith('image/') ? '🖼️' : '📄';
            return `
              <div class="file-item">
                <div class="file-item-info">
                  <span class="file-icon">${icon}</span>
                  <div style="flex: 1; min-width: 0;">
                    <div class="file-item-name">${file.name}</div>
                    <div class="file-item-size">${formatFileSize(file.size)}</div>
                  </div>
                </div>
                <a href="${file.data}" download="${file.name}" class="btn btn-primary btn-small" style="text-decoration: none; white-space: nowrap;">Download</a>
              </div>
            `;
          }).join('')}
        </div>
        <button class="btn btn-secondary" onclick="closeReportsModal()" style="margin-top: 1.5rem; width: 100%;">Close</button>
      </div>
    `;
    
    modal.innerHTML = modalContent;
    document.body.appendChild(modal);
    
    // Store reference for closing
    window.currentReportsModal = modal;
    
    // Close on outside click
    modal.addEventListener('click', (e) => {
      if (e.target === modal) {
        closeReportsModal();
      }
    });
  }).catch(error => {
    console.error('❌ Error loading reports:', error);
    alert('Error loading reports');
  });
}

function closeReportsModal() {
  if (window.currentReportsModal) {
    window.currentReportsModal.remove();
    window.currentReportsModal = null;
  }
}

function clearSelectedFiles() {
  console.log('🧹 Clearing selected files');
  selectedFiles = [];
  const fileListDiv = document.getElementById('file-list');
  if (fileListDiv) {
    fileListDiv.innerHTML = '<div style="color: var(--text-dim); font-size: 0.8rem; margin-top: 0.5rem; padding: 0.5rem;">No files selected</div>';
  }
}

function loadSelectedFiles(reports) {
  console.log('📥 Loading selected files:', reports ? reports.length : 0);
  selectedFiles = Array.isArray(reports) ? reports : [];
  displayFileList();
}

function getSelectedFiles() {
  console.log('📤 Getting selected files:', selectedFiles.length);
  return selectedFiles;
}

function calculateStatus(medicines) {
  const status = medicines && medicines.trim() ? 'Complete' : 'In Progress';
  console.log('🎯 Status calculated:', status, '(Medicines:', medicines ? 'filled' : 'empty', ')');
  return status;
}

// Make functions globally available
window.handleFileSelect = handleFileSelect;
window.displayFileList = displayFileList;
window.removeFile = removeFile;
window.formatFileSize = formatFileSize;
window.viewReports = viewReports;
window.closeReportsModal = closeReportsModal;
window.clearSelectedFiles = clearSelectedFiles;
window.loadSelectedFiles = loadSelectedFiles;
window.getSelectedFiles = getSelectedFiles;
window.calculateStatus = calculateStatus;

// Log when script is fully loaded
console.log('✅ reports-status.js loaded successfully');
console.log('📦 Exported functions:', Object.keys(window).filter(k => ['handleFileSelect', 'displayFileList', 'removeFile', 'clearSelectedFiles', 'loadSelectedFiles', 'getSelectedFiles', 'calculateStatus', 'viewReports'].includes(k)));

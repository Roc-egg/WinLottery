<#
.SYNOPSIS
只读验证 WinLottery Windows MSI 的发布元数据与关键载荷。
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$MsiPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$ExpectedVersion,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[{]?[0-9A-Fa-f-]{36}[}]?$')]
    [string]$ExpectedUpgradeCode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
调用 Windows Installer COM 对象的方法，并统一处理无参数调用。
#>
function Invoke-ComMethod {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Target,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [object[]]$Arguments = @()
    )

    $invokeArguments = if ($Arguments.Count -eq 0) { $null } else { $Arguments }
    return $Target.GetType().InvokeMember(
        $Name,
        [System.Reflection.BindingFlags]::InvokeMethod,
        $null,
        $Target,
        $invokeArguments
    )
}

<#
.SYNOPSIS
读取 Windows Installer COM 对象的属性。
#>
function Get-ComProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Target,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [object[]]$Arguments = @()
    )

    $invokeArguments = if ($Arguments.Count -eq 0) { $null } else { $Arguments }
    return $Target.GetType().InvokeMember(
        $Name,
        [System.Reflection.BindingFlags]::GetProperty,
        $null,
        $Target,
        $invokeArguments
    )
}

<#
.SYNOPSIS
执行只返回一列的 MSI 查询，并返回全部非空结果。
#>
function Get-MsiColumnValues {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Database,

        [Parameter(Mandatory = $true)]
        [string]$Query
    )

    $view = Invoke-ComMethod -Target $Database -Name 'OpenView' -Arguments @($Query)
    try {
        Invoke-ComMethod -Target $view -Name 'Execute' | Out-Null
        $values = [System.Collections.Generic.List[string]]::new()
        while ($true) {
            $record = Invoke-ComMethod -Target $view -Name 'Fetch'
            if ($null -eq $record) {
                break
            }

            $value = [string](Get-ComProperty -Target $record -Name 'StringData' -Arguments @(1))
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $values.Add($value)
            }
        }
        return $values.ToArray()
    }
    finally {
        Invoke-ComMethod -Target $view -Name 'Close' | Out-Null
    }
}

<#
.SYNOPSIS
读取 MSI 查询的唯一结果，缺失或重复时立即失败。
#>
function Get-RequiredMsiValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Database,

        [Parameter(Mandatory = $true)]
        [string]$Query,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $values = @(Get-MsiColumnValues -Database $Database -Query $Query)
    if ($values.Count -ne 1) {
        throw "$Label must have exactly one value, found $($values.Count)."
    }
    return $values[0]
}

<#
.SYNOPSIS
比较 MSI 实际值与冻结值，比较失败时给出明确字段名。
#>
function Assert-MsiValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,

        [Parameter(Mandatory = $true)]
        [string]$Actual,

        [Parameter(Mandatory = $true)]
        [string]$Expected
    )

    if (-not [string]::Equals($Actual, $Expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label mismatch: expected '$Expected', actual '$Actual'."
    }
}

<#
.SYNOPSIS
转义 MSI SQL 字符串字面量中的单引号。
#>
function ConvertTo-MsiSqlLiteral {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    return $Value.Replace("'", "''")
}

<#
.SYNOPSIS
读取 MSI Property 表中的必需属性。
#>
function Get-MsiPropertyValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Database,

        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    $escapedName = ConvertTo-MsiSqlLiteral -Value $PropertyName
    return Get-RequiredMsiValue `
        -Database $Database `
        -Query "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$escapedName'" `
        -Label "Property.$PropertyName"
}

<#
.SYNOPSIS
确认开始菜单快捷方式目录最终隶属于 ProgramMenuFolder。
#>
function Assert-StartMenuDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Database,

        [Parameter(Mandatory = $true)]
        [string]$ShortcutDirectory
    )

    $currentDirectory = $ShortcutDirectory
    for ($depth = 0; $depth -lt 8; $depth++) {
        if ($currentDirectory -eq 'ProgramMenuFolder') {
            return
        }

        $escapedDirectory = ConvertTo-MsiSqlLiteral -Value $currentDirectory
        $currentDirectory = Get-RequiredMsiValue `
            -Database $Database `
            -Query "SELECT ``Directory_Parent`` FROM ``Directory`` WHERE ``Directory`` = '$escapedDirectory'" `
            -Label "Directory.$currentDirectory"
    }

    throw 'Start menu shortcut is not rooted at ProgramMenuFolder.'
}

$resolvedMsiPath = (Resolve-Path -LiteralPath $MsiPath).Path
$msiFile = Get-Item -LiteralPath $resolvedMsiPath
if ($msiFile.Extension -ne '.msi' -or $msiFile.Length -le 0) {
    throw 'MSI file is missing or empty.'
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = Invoke-ComMethod -Target $installer -Name 'OpenDatabase' -Arguments @($resolvedMsiPath, 0)

Assert-MsiValue -Label 'ProductName' -Actual (Get-MsiPropertyValue $database 'ProductName') -Expected 'WinLottery'
Assert-MsiValue -Label 'ProductVersion' -Actual (Get-MsiPropertyValue $database 'ProductVersion') -Expected $ExpectedVersion
Assert-MsiValue -Label 'Manufacturer' -Actual (Get-MsiPropertyValue $database 'Manufacturer') -Expected 'roc'

$normalizedUpgradeCode = '{' + $ExpectedUpgradeCode.Trim('{}').ToUpperInvariant() + '}'
Assert-MsiValue -Label 'UpgradeCode' -Actual (Get-MsiPropertyValue $database 'UpgradeCode') -Expected $normalizedUpgradeCode

<# Upgrade 表必须实际引用冻结标识，不能只在 Property 表中保留无效元数据。 #>
$upgradeTableCodes = @(Get-MsiColumnValues -Database $database -Query 'SELECT `UpgradeCode` FROM `Upgrade`')
if ($upgradeTableCodes.Count -eq 0 -or ($upgradeTableCodes | Where-Object { $_ -ne $normalizedUpgradeCode })) {
    throw 'MSI Upgrade table does not consistently use the expected UpgradeCode.'
}

$summary = Get-ComProperty -Target $database -Name 'SummaryInformation'
$template = [string](Get-ComProperty -Target $summary -Name 'Property' -Arguments @(7))
if (-not $template.StartsWith('x64;', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "MSI template must target x64, actual '$template'."
}

$installDirectoryParent = Get-RequiredMsiValue `
    -Database $database `
    -Query 'SELECT `Directory_Parent` FROM `Directory` WHERE `Directory` = ''INSTALLDIR''' `
    -Label 'INSTALLDIR parent'
Assert-MsiValue -Label 'INSTALLDIR parent' -Actual $installDirectoryParent -Expected 'LocalAppDataFolder'

<# 当前用户安装不得开放目录选择，否则用户可选到其他盘根目录并破坏卸载回滚权限。 #>
$installDirectoryDialogs = @(
    Get-MsiColumnValues `
        -Database $database `
        -Query 'SELECT `Dialog` FROM `Dialog` WHERE `Dialog` = ''InstallDirDlg'''
)
if ($installDirectoryDialogs.Count -ne 0) {
    throw 'Per-user MSI must not expose InstallDirDlg.'
}

$shortcutDirectory = Get-RequiredMsiValue `
    -Database $database `
    -Query 'SELECT `Directory_` FROM `Shortcut`' `
    -Label 'Start menu shortcut'
Assert-StartMenuDirectory -Database $database -ShortcutDirectory $shortcutDirectory

$payloadNames = @(Get-MsiColumnValues -Database $database -Query 'SELECT `FileName` FROM `File`') | ForEach-Object {
    ($_ -split '\|')[-1]
}
foreach ($requiredPattern in @('WinLottery.exe', 'onnxruntime-1.29.0-*.jar', 'recognition-jvm-*.jar')) {
    if (-not ($payloadNames | Where-Object { $_ -like $requiredPattern })) {
        throw "MSI payload does not contain '$requiredPattern'."
    }
}

Write-Output "WINLOTTERY_WINDOWS_MSI_OK`t$ExpectedVersion`tx64`tper-user`tstart-menu"

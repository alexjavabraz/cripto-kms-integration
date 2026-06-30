// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * TokenizaERC20 — contrato ERC-20 padrão com mint, burn, pause e controle de owner.
 * Construtor: (string name, string symbol, uint8 decimals, uint256 initialSupply, address owner)
 */
contract TokenizaERC20 {

    string  public name;
    string  public symbol;
    uint8   public decimals;
    uint256 public totalSupply;
    address public owner;
    bool    public paused;

    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);
    event Paused(address account);
    event Unpaused(address account);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "ERC20: not owner");
        _;
    }

    modifier whenNotPaused() {
        require(!paused, "ERC20: paused");
        _;
    }

    constructor(
        string memory _name,
        string memory _symbol,
        uint8  _decimals,
        uint256 _initialSupply,
        address _owner
    ) {
        name     = _name;
        symbol   = _symbol;
        decimals = _decimals;
        owner    = _owner;
        if (_initialSupply > 0) {
            totalSupply          = _initialSupply;
            balanceOf[_owner]    = _initialSupply;
            emit Transfer(address(0), _owner, _initialSupply);
        }
    }

    function transfer(address to, uint256 amount) external whenNotPaused returns (bool) {
        _transfer(msg.sender, to, amount);
        return true;
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) external whenNotPaused returns (bool) {
        require(allowance[from][msg.sender] >= amount, "ERC20: insufficient allowance");
        allowance[from][msg.sender] -= amount;
        _transfer(from, to, amount);
        return true;
    }

    function mint(address to, uint256 amount) external onlyOwner whenNotPaused returns (bool) {
        totalSupply      += amount;
        balanceOf[to]    += amount;
        emit Transfer(address(0), to, amount);
        return true;
    }

    // burn(uint256) — queima da carteira de quem chama (msg.sender)
    function burn(uint256 amount) external whenNotPaused returns (bool) {
        require(balanceOf[msg.sender] >= amount, "ERC20: insufficient balance");
        balanceOf[msg.sender] -= amount;
        totalSupply           -= amount;
        emit Transfer(msg.sender, address(0), amount);
        return true;
    }

    function pause() external onlyOwner {
        paused = true;
        emit Paused(msg.sender);
    }

    function unpause() external onlyOwner {
        paused = false;
        emit Unpaused(msg.sender);
    }

    function transferOwnership(address newOwner) external onlyOwner {
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }

    function _transfer(address from, address to, uint256 amount) internal {
        require(balanceOf[from] >= amount, "ERC20: insufficient balance");
        balanceOf[from] -= amount;
        balanceOf[to]   += amount;
        emit Transfer(from, to, amount);
    }
}
